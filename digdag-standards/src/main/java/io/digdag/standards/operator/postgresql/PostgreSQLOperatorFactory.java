package io.digdag.standards.operator.postgresql;

import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.inject.Inject;
import io.digdag.client.config.Config;
import io.digdag.client.config.ConfigElement;
import io.digdag.client.config.ConfigException;
import io.digdag.spi.Operator;
import io.digdag.spi.OperatorFactory;
import io.digdag.spi.TaskExecutionException;
import io.digdag.spi.TaskRequest;
import io.digdag.spi.TaskResult;
import io.digdag.spi.TemplateEngine;
import io.digdag.standards.operator.jdbc.CSVWriter;
import io.digdag.standards.operator.jdbc.JdbcColumn;
import io.digdag.standards.operator.jdbc.JdbcQueryHelper;
import io.digdag.standards.operator.jdbc.JdbcQueryTxHelper;
import io.digdag.standards.operator.jdbc.JdbcSchema;
import io.digdag.standards.operator.jdbc.QueryResultHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;

public class PostgreSQLOperatorFactory
        implements OperatorFactory
{
    private static final Logger logger = LoggerFactory.getLogger(PostgreSQLOperatorFactory.class);

    private final TemplateEngine templateEngine;

    @Inject
    public PostgreSQLOperatorFactory(TemplateEngine templateEngine)
    {
        this.templateEngine = checkNotNull(templateEngine, "templateEngine");
    }

    public String getType()
    {
        return "postgresql";
    }

    @Override
    public Operator newTaskExecutor(Path workspacePath, TaskRequest request)
    {
        return new PostgreSQLOperator(workspacePath, templateEngine, request);
    }

    private static class PostgreSQLOperator
        extends PostgreSQLBaseOperator
    {
        private static final String QUERY_ID = "queryId";
        private static final String QUERY_STATUS = "queryStatus";
        private static final String QUERY_STATUS_RUNNING = "running";
        private static final String QUERY_STATUS_FINISHED = "finished";

        private JdbcQueryHelper.QueryType queryType;
        private String query;
        private Optional<String> queryStatus;
        private Optional<QueryResultHandler> queryResultHandler;

        public PostgreSQLOperator(Path workspacePath, TemplateEngine templateEngine, TaskRequest request)
        {
            super(workspacePath, templateEngine, request);
        }

        @Override
        public TaskResult runTask()
        {
            Config params = request.getConfig().mergeDefault(request.getConfig().getNestedOrGetEmpty("postgresql"));
            Config state = request.getLastStateParams().deepCopy();

            query = templateEngine.templateCommand(workspacePath, params, "query", UTF_8);
            Optional<String> statusTable = Optional.of(params.get("status_table", String.class, "__digdag_status"));
            boolean updateQuery = params.get("update_query", Boolean.class, false);

            Optional<PostgreSQLTableParam> insertInto = params.getOptional("insert_into", PostgreSQLTableParam.class);
            Optional<PostgreSQLTableParam> createTable = params.getOptional("create_table", PostgreSQLTableParam.class);
            Optional<PostgreSQLTableParam> updateTable = params.getOptional("update_table", PostgreSQLTableParam.class);
            Optional<List<String>> uniqKeys = params.getOptional("uniq_keys", String.class).transform(s -> Arrays.asList(s.split("\\s+,\\s+")));
            int manipulateTableOperationCount = 0;
            if (updateQuery) {
                manipulateTableOperationCount += 1;
            }
            if (insertInto.isPresent()) {
                manipulateTableOperationCount += 1;
            }
            if (createTable.isPresent()) {
                manipulateTableOperationCount += 1;
            }
            if (updateTable.isPresent()) {
                manipulateTableOperationCount += 1;
                if (!uniqKeys.isPresent()) {
                    throw new ConfigException("key_column is required when update_table is used");
                }
            }
            if (manipulateTableOperationCount > 1) {
                throw new ConfigException("Can't use more than 2 of insert_into/create_table/update_table");
            }
            Optional<String> downloadFile = params.getOptional("download_file", String.class);
            if (downloadFile.isPresent() && manipulateTableOperationCount > 0) {
                throw new ConfigException("Can't use download_file with insert_into/create_table/update_table");
            }

            queryResultHandler = Optional.absent();
            Optional<String> destTable = Optional.absent();

            if (updateQuery) {
                queryType = JdbcQueryHelper.QueryType.UPDATE_QUERY;
            }
            else if (insertInto.isPresent()) {
                queryType = JdbcQueryHelper.QueryType.WITH_INSERT_INTO;
                destTable = Optional.of(insertInto.get().toString());
            }
            else if (createTable.isPresent()) {
                queryType = JdbcQueryHelper.QueryType.WITH_CREATE_TABLE;
                destTable = Optional.of(createTable.get().toString());
            }
            else if (updateTable.isPresent()) {
                queryType = JdbcQueryHelper.QueryType.WITH_UPDATE_TABLE;
                destTable = Optional.of(updateTable.get().toString());
            }
            else {
                queryType = JdbcQueryHelper.QueryType.SELECT_ONLY;
                if (downloadFile.isPresent()) {
                    queryResultHandler = Optional.of(getResultCsvDownloader(downloadFile.get()));
                }
                else {
                    queryResultHandler = Optional.of(getResultCsvPrinter());
                }
            }

            Optional<String> queryId = state.getOptional(QUERY_ID, String.class);
            queryStatus = state.getOptional(QUERY_STATUS, String.class);

            if (!queryId.isPresent()) {
                logger.debug("This job hasn't created a query ID yet. Generating it...");

                // Not started yet
                String newQueryId = UUID.randomUUID().toString();
                state.set(QUERY_STATUS, QUERY_STATUS_RUNNING);
                state.set(QUERY_ID, newQueryId);
                throw TaskExecutionException.ofNextPolling(0, ConfigElement.copyOf(state));
            }

            try (PostgreSQLConnection connection = getPostgreSQLConnection(params)) {
                JdbcQueryTxHelper queryHelper = new JdbcQueryTxHelper(connection, queryId.get(), statusTable);
                queryHelper.createStatusTable();

                // query ID is already generated
                switch (queryStatus.get()) {
                    case QUERY_STATUS_RUNNING:
                        JdbcQueryTxHelper.Status status = queryHelper.getStatusRecord();
                        if (status == null) {
                            queryHelper.addStatusRecord();
                        }

                        switch (queryHelper.getStatusRecord()) {
                            case READY:
                                logger.debug("This job hasn't started the query yet. Starting it...");
                                queryHelper.executeQuery(queryType, query, destTable, uniqKeys, queryResultHandler);
                                throw TaskExecutionException.ofNextPolling(0, ConfigElement.copyOf(state));

                            case FINISHED:
                                logger.debug("This job has finished already");
                                state.set(QUERY_STATUS, QUERY_STATUS_FINISHED);
                                throw TaskExecutionException.ofNextPolling(0, ConfigElement.copyOf(state));

                            default:
                                throw new IllegalStateException("Shouldn't reach here: " + queryHelper.getStatusRecord());
                        }

                    case QUERY_STATUS_FINISHED:
                        queryHelper.dropStatusRecord();
                        break;

                    default:
                        throw new IllegalStateException("Shouldn't reach here: " + queryStatus.get());
                }
            }
            catch (TaskExecutionException e) {
                logger.debug("Moving to the next phase...");
                throw e;
            }
            catch (Exception e) {
                throw new RuntimeException("Failed to issue a query: request=" + request, e);
            }

            return TaskResult.defaultBuilder(request).build();
        }

        interface WriterGenerator
        {
            Writer generate()
                    throws IOException;
        }

        private QueryResultHandler getResultCsvPrinter()
        {
            return getResultWriter(() -> new BufferedWriter(new OutputStreamWriter(System.out)));
        }

        private QueryResultHandler getResultCsvDownloader(String downloadFile)
        {
            return getResultWriter(() -> workspace.newBufferedWriter(downloadFile, UTF_8));
        }

        private QueryResultHandler getResultWriter(WriterGenerator writerGenerator)
        {
            return new QueryResultHandler()
            {
                private CSVWriter csvWriter;
                private JdbcSchema schema;

                @Override
                public void before()
                {
                    try {
                        csvWriter = new CSVWriter(writerGenerator.generate());
                    }
                    catch (IOException e) {
                        throw new RuntimeException("Failed to create CSVWriter", e);
                    }
                }

                @Override
                public void schema(JdbcSchema schema)
                {
                    this.schema = schema;
                    try {
                        csvWriter.addCsvHeader(schema.getColumns().stream().map(JdbcColumn::getName).collect(Collectors.toList()));
                    }
                    catch (IOException e) {
                        Throwables.propagate(e);
                    }
                }

                @Override
                public void handleRow(List<Object> row)
                {
                    try {
                        csvWriter.addCsvRow(schema.getColumns().stream().map(JdbcColumn::getTypeGroup).collect(Collectors.toList()), row);
                    }
                    catch (IOException e) {
                        Throwables.propagate(e);
                    }
                }

                @Override
                public void after()
                {
                    try {
                        csvWriter.close();
                    }
                    catch (Exception e) {
                        logger.warn("Failed to close {}", csvWriter);
                    }
                }
            };
        }
    }
}
