package io.kestra.plugin.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.Rethrow;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.sql.*;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Consumer;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractJdbcQuery extends Task {
    @Schema(
        title = "The sql query to run"
    )
    @PluginProperty(dynamic = true)
    private String sql;

    @Builder.Default
    @Schema(
        title = "Whether to fetch data row from the query result to a file in internal storage." +
            " File will be saved as Amazon Ion (text format)." +
            " \n" +
            " See <a href=\"http://amzn.github.io/ion-docs/\">Amazon Ion documentation</a>" +
            " This parameter is evaluated after 'fetchOne' but before 'fetch'."
    )
    @PluginProperty(dynamic = true)
    private final Boolean store = false;

    @Builder.Default
    @Schema(
        title = "Whether to fetch only one data row from the query result to the task output." +
            " This parameter is evaluated before 'store' and 'fetch'."
    )
    private final Boolean fetchOne = false;

    @Builder.Default
    @Schema(
        title = "Whether to fetch the data from the query result to the task output" +
            " This parameter is evaluated after 'fetchOne' and 'store'."
    )
    private final Boolean fetch = false;

    @Schema(
        title = "The jdbc url to connect to the database"
    )
    @PluginProperty(dynamic = true)
    private String url;

    @Schema(
        title = "The database user"
    )
    @PluginProperty(dynamic = true)
    private String username;

    @Schema(
        title = "The database user's password"
    )
    @PluginProperty(dynamic = true)
    private String password;

    @Schema(
        title = "The time zone id to use for date/time manipulation. Default value is the worker default zone id."
    )
    private String timeZoneId;

    @Schema(
        title = "Number of rows that should be fetched",
        description = "Gives the JDBC driver a hint as to the number of rows that should be fetched from the database " +
            "when more rows are needed for this ResultSet object. If the fetch size specified is zero, the JDBC driver " +
            "ignores the value and is free to make its own best guess as to what the fetch size should be. Ignored if " +
            "`autoCommit` is false."
    )
    @PluginProperty(dynamic = false)
    @Builder.Default
    private final Integer fetchSize = 10000;

    @Schema(
        title = "Number of rows that should be fetched",
        description = "Sets this connection's auto-commit mode to the given state. If a connection is in auto-commit " +
            "mode, then all its SQL statements will be executed and committed as individual transactions. Otherwise, " +
            "its SQL statements are grouped into transactions that are terminated by a call to either the method commit" +
            "or the method rollback. By default, new connections are in auto-commit mode except if you are using a " +
            "`store` properties that will disabled autocommit whenever this properties values."
    )
    @PluginProperty(dynamic = false)
    private final Boolean autoCommit = true;

    private static final ObjectMapper MAPPER = JacksonMapper.ofIon();

    protected abstract AbstractCellConverter getCellConverter(ZoneId zoneId);

    /**
     * JDBC driver may be auto-registered. See https://docs.oracle.com/javase/8/docs/api/java/sql/DriverManager.html
     *
     * @throws SQLException registerDrivers failed
     */
    protected abstract void registerDriver() throws SQLException;

    public AbstractJdbcQuery.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        registerDriver();

        ZoneId zoneId = TimeZone.getDefault().toZoneId();
        if (this.timeZoneId != null) {
            zoneId = ZoneId.of(timeZoneId);
        }

        AbstractCellConverter cellConverter = getCellConverter(zoneId);

        try (
            Connection conn = DriverManager.getConnection(runContext.render(this.url), runContext.render(this.username), runContext.render(this.password));
            Statement stmt = conn.createStatement(
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY
            )
        ) {
            if (this.store) {
                conn.setAutoCommit(false);
            } else {
                conn.setAutoCommit(this.autoCommit);
            }

            stmt.setFetchSize(fetchSize);

            String sql = runContext.render(this.sql);
            boolean isResult = stmt.execute(sql);

            logger.debug("Starting query: {}", sql);

            try(ResultSet rs = stmt.getResultSet()) {
                Output.OutputBuilder output = Output.builder();
                long size = 0;

                if (isResult) {
                    if (this.fetchOne) {
                        output
                            .row(fetchResult(rs, cellConverter, conn))
                            .size(1L);
                        size = 1;

                    } else if (this.store) {
                        File tempFile = File.createTempFile(this.getClass().getSimpleName().toLowerCase() + "_", ".ion");
                        BufferedWriter fileWriter = new BufferedWriter(new FileWriter(tempFile));
                        size = fetchToFile(stmt, rs, fileWriter, cellConverter, conn);
                        fileWriter.close();
                        output
                            .uri(runContext.putTempFile(tempFile))
                            .size(size);
                    } else if (this.fetch) {
                        List<Map<String, Object>> maps = new ArrayList<>();
                        size = fetchResults(stmt, rs, maps, cellConverter, conn);
                        output
                            .rows(maps)
                            .size(size);
                    }
                }

                runContext.metric(Counter.of("fetch.size",  size, this.tags()));

                return output.build();
            }
        }
    }

    private String[] tags() {
        return new String[]{
            "fetch", this.fetch || this.fetchOne ? "true" : "false",
            "store", this.store ? "true" : "false",
        };
    }

    protected Map<String, Object> fetchResult(ResultSet rs, AbstractCellConverter cellConverter, Connection connection) throws SQLException {
        rs.next();
        return mapResultSetToMap(rs, cellConverter, connection);
    }

    protected long fetchResults(Statement stmt, ResultSet rs, List<Map<String, Object>> maps, AbstractCellConverter cellConverter, Connection connection) throws SQLException {
        return fetch(stmt, rs, Rethrow.throwConsumer(maps::add), cellConverter, connection);
    }

    protected long fetchToFile(Statement stmt, ResultSet rs, BufferedWriter writer, AbstractCellConverter cellConverter, Connection connection) throws SQLException, IOException {
        return fetch(
            stmt,
            rs,
            Rethrow.throwConsumer(map -> {
                final String s = MAPPER.writeValueAsString(map);
                writer.write(s);
                writer.write("\n");
            }),
            cellConverter,
            connection
        );
    }

    private long fetch(Statement stmt, ResultSet rs, Consumer<Map<String, Object>> c, AbstractCellConverter cellConverter, Connection connection) throws SQLException {
        boolean isResult;
        long count = 0;

        do {
            while (rs.next()) {
                Map<String, Object> map = mapResultSetToMap(rs, cellConverter, connection);
                c.accept(map);
                count++;
            }
            isResult = stmt.getMoreResults();
        } while (isResult);

        return count;
    }

    private Map<String, Object> mapResultSetToMap(ResultSet rs, AbstractCellConverter cellConverter, Connection connection) throws SQLException {
        int columnsCount = rs.getMetaData().getColumnCount();
        Map<String, Object> map = new LinkedHashMap<>();

        for (int i = 1; i <= columnsCount; i++) {
            map.put(rs.getMetaData().getColumnName(i), convertCell(i, rs, cellConverter, connection));
        }

        return map;
    }

    private Object convertCell(int columnIndex, ResultSet rs, AbstractCellConverter cellConverter, Connection connection) throws SQLException {
        return cellConverter.convertCell(columnIndex, rs, connection);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Map containing the first row of fetched data",
            description = "Only populated if 'fetchOne' parameter is set to true."
        )
        private final Map<String, Object> row;

        @Schema(
            title = "Lit of map containing rows of fetched data",
            description = "Only populated if 'fetch' parameter is set to true."
        )
        private final List<Map<String, Object>> rows;

        @Schema(
            title = "The url of the result file on kestra storage (.ion file / Amazon Ion text format)",
            description = "Only populated if 'store' is set to true."
        )
        private final URI uri;

        @Schema(
            title = "The size of the fetched rows",
            description = "Only populated if 'store' or 'fetch' parameter is set to true."
        )
        private final Long size;
    }
}
