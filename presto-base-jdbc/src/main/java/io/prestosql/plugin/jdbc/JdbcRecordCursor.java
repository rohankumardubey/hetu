/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.plugin.jdbc;

import com.google.common.base.VerifyException;
import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.RecordCursor;
import io.prestosql.spi.type.Type;
import org.joda.time.DateTime;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.plugin.jdbc.JdbcErrorCode.JDBC_ERROR;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class JdbcRecordCursor
        implements RecordCursor
{
    private static final Logger log = Logger.get(JdbcRecordCursor.class);

    private final JdbcColumnHandle[] columnHandles;
    private final BooleanReadFunction[] booleanReadFunctions;
    private final DoubleReadFunction[] doubleReadFunctions;
    private final LongReadFunction[] longReadFunctions;
    private final SliceReadFunction[] sliceReadFunctions;
    private final BlockReadFunction[] blockReadFunctions;

    private final JdbcClient jdbcClient;
    private final Connection connection;
    private final PreparedStatement statement;
    private final ResultSet resultSet;
    private boolean closed;
    private DateTime start;
    private DateTime connected;
    private DateTime executed;
    private long fetchCnt;

    public JdbcRecordCursor(JdbcClient jdbcClient, ConnectorSession session, JdbcSplit split, JdbcTableHandle table, List<JdbcColumnHandle> columnHandles)
    {
        start = DateTime.now();
        this.jdbcClient = requireNonNull(jdbcClient, "jdbcClient is null");

        this.columnHandles = columnHandles.toArray(new JdbcColumnHandle[0]);

        booleanReadFunctions = new BooleanReadFunction[columnHandles.size()];
        doubleReadFunctions = new DoubleReadFunction[columnHandles.size()];
        longReadFunctions = new LongReadFunction[columnHandles.size()];
        sliceReadFunctions = new SliceReadFunction[columnHandles.size()];
        blockReadFunctions = new BlockReadFunction[columnHandles.size()];

        try {
            connection = jdbcClient.getConnection(JdbcIdentity.from(session), split);
            connected = DateTime.now();

            for (int i = 0; i < this.columnHandles.length; i++) {
                ColumnMapping columnMapping = jdbcClient.toPrestoType(session, connection, columnHandles.get(i).getJdbcTypeHandle())
                        .orElseThrow(() -> new VerifyException("Unsupported column type"));
                Class<?> javaType = columnMapping.getType().getJavaType();
                ReadFunction readFunction = columnMapping.getReadFunction();

                if (javaType == boolean.class) {
                    booleanReadFunctions[i] = (BooleanReadFunction) readFunction;
                }
                else if (javaType == double.class) {
                    doubleReadFunctions[i] = (DoubleReadFunction) readFunction;
                }
                else if (javaType == long.class) {
                    longReadFunctions[i] = (LongReadFunction) readFunction;
                }
                else if (javaType == Slice.class) {
                    sliceReadFunctions[i] = (SliceReadFunction) readFunction;
                }
                else if (javaType == Block.class) {
                    blockReadFunctions[i] = (BlockReadFunction) readFunction;
                }
                else {
                    throw new IllegalStateException(format("Unsupported java type %s", javaType));
                }
            }

            statement = jdbcClient.buildSql(session, connection, split, table, columnHandles);
            log.debug("Executing: %s", statement.toString());
            resultSet = statement.executeQuery();
            executed = DateTime.now();
        }
        catch (SQLException | RuntimeException e) {
            throw handleSqlException(e);
        }
    }

    @Override
    public long getReadTimeNanos()
    {
        return 0;
    }

    @Override
    public long getCompletedBytes()
    {
        return 0;
    }

    @Override
    public Type getType(int field)
    {
        return columnHandles[field].getColumnType();
    }

    @Override
    public boolean advanceNextPosition()
    {
        if (closed) {
            return false;
        }

        try {
            fetchCnt++;
            return resultSet.next();
        }
        catch (SQLException | RuntimeException e) {
            throw handleSqlException(e);
        }
    }

    @Override
    public boolean getBoolean(int field)
    {
        checkState(!closed, "cursor is closed");
        try {
            return booleanReadFunctions[field].readBoolean(resultSet, field + 1);
        }
        catch (SQLException | RuntimeException e) {
            throw handleSqlException(e);
        }
    }

    @Override
    public long getLong(int field)
    {
        checkState(!closed, "cursor is closed");
        try {
            return longReadFunctions[field].readLong(resultSet, field + 1);
        }
        catch (SQLException | RuntimeException e) {
            throw handleSqlException(e);
        }
    }

    @Override
    public double getDouble(int field)
    {
        checkState(!closed, "cursor is closed");
        try {
            return doubleReadFunctions[field].readDouble(resultSet, field + 1);
        }
        catch (SQLException | RuntimeException e) {
            throw handleSqlException(e);
        }
    }

    @Override
    public Slice getSlice(int field)
    {
        checkState(!closed, "cursor is closed");
        try {
            return sliceReadFunctions[field].readSlice(resultSet, field + 1);
        }
        catch (SQLException | RuntimeException e) {
            throw handleSqlException(e);
        }
    }

    @Override
    public Object getObject(int field)
    {
        checkState(!closed, "cursor is closed");
        try {
            return blockReadFunctions[field].readBlock(resultSet, field + 1);
        }
        catch (SQLException | RuntimeException e) {
            throw handleSqlException(e);
        }
    }

    @Override
    public boolean isNull(int field)
    {
        checkState(!closed, "cursor is closed");
        checkArgument(field < columnHandles.length, "Invalid field index");

        try {
            // JDBC is kind of dumb: we need to read the field and then ask
            // if it was null, which means we are wasting effort here.
            // We could save the result of the field access if it matters.
            resultSet.getObject(field + 1);

            return resultSet.wasNull();
        }
        catch (SQLException | RuntimeException e) {
            throw handleSqlException(e);
        }
    }

    @SuppressWarnings("UnusedDeclaration")
    @Override
    public void close()
    {
        if (closed) {
            return;
        }
        closed = true;
        DateTime toClose = DateTime.now();

        // use try with resources to close everything properly
        try (Statement stat = this.statement;
                ResultSet result = this.resultSet) {
            checkState(closed, "just for check style handle");
        }
        catch (SQLException e) {
            // ignore exception from close
            log.warn(e.toString());
        }

        try (Connection conn = this.connection) {
            if (conn != null) {
                jdbcClient.abortReadConnection(conn);

                DateTime closedTime = DateTime.now();
                log.debug("connect open cost %dms, execute cost %dms, fetch data cost %dms, connect close cost %dms, total cost %dms, fetch rows %d.",
                        connected.getMillis() - start.getMillis(),
                        executed.getMillis() - connected.getMillis(),
                        toClose.getMillis() - executed.getMillis(),
                        closedTime.getMillis() - toClose.getMillis(),
                        closedTime.getMillis() - start.getMillis(), fetchCnt - 1);
            }
        }
        catch (SQLException e) {
            // ignore exception from close
            log.warn(e.toString());
        }
    }

    private RuntimeException handleSqlException(Exception e)
    {
        try {
            close();
        }
        catch (Exception closeException) {
            // Self-suppression not permitted
            if (e != closeException) {
                e.addSuppressed(closeException);
            }
        }
        return new PrestoException(JDBC_ERROR, e);
    }
}
