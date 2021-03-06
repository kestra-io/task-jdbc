package io.kestra.plugin.jdbc.mysql;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.jdbc.AbstractCellConverter;
import io.kestra.plugin.jdbc.AbstractJdbcQuery;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.ZoneId;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "MySQL Query Task.",
    description = "Currently supported types are the following ones : \n" +
        " - serial,\n" +
        " - tinyint,\n" +
        " - char(n),\n" +
        " - varchar(n),\n" +
        " - text,\n" +
        " - bigint,\n" +
        " - bit(n),\n" +
        " - float,\n" +
        " - double,\n" +
        " - numeric,\n" +
        " - decimal,\n" +
        " - date,\n" +
        " - datetime(n),\n" +
        " - time,\n" +
        " - timestamp(n),\n" +
        " - year(n),\n" +
        " - json,\n" +
        " - blob"
)
@Plugin(
    examples = {
        @Example(
            title = "Request a PostgresSQL Database and fetch a row as outputs",
            code = {
                "url: jdbc:postgresql://127.0.0.1:56982/",
                "username: postgres",
                "password: pg_passwd",
                "sql: select * from mysql_types",
                "fetchOne: true",
            }
        )
    }
)
public class Query extends AbstractJdbcQuery implements RunnableTask<AbstractJdbcQuery.Output> {
    @Override
    protected AbstractCellConverter getCellConverter(ZoneId zoneId) {
        return new MysqlCellConverter(zoneId);
    }

    @Override
    protected void registerDriver() throws SQLException {
        DriverManager.registerDriver(new com.mysql.cj.jdbc.Driver());
    }

    @Override
    public Output run(RunContext runContext) throws Exception {
        return super.run(runContext);
    }
}
