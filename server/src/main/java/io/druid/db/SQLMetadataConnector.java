/*
 * Druid - a distributed column store.
 * Copyright (C) 2012, 2013  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package io.druid.db;

import com.google.common.base.Supplier;
import com.metamx.common.ISE;
import com.metamx.common.logger.Logger;
import org.apache.commons.dbcp.BasicDataSource;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.IDBI;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.tweak.HandleCallback;
import org.skife.jdbi.v2.tweak.ResultSetMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public abstract class SQLMetadataConnector implements MetadataStorageConnector
{
  private static final Logger log = new Logger(SQLMetadataConnector.class);
  private static final String PAYLOAD_TYPE = "BLOB";

  private final Supplier<MetadataStorageConnectorConfig> config;
  private final Supplier<MetadataStorageTablesConfig> dbTables;

  public SQLMetadataConnector(Supplier<MetadataStorageConnectorConfig> config,
                              Supplier<MetadataStorageTablesConfig> dbTables)
  {
    this.config = config;
    this.dbTables = dbTables;
  }

  protected String getPayloadType() {
    return PAYLOAD_TYPE;
  }

  protected abstract String getSerialType();

  public void createTable(final IDBI dbi, final String tableName, final String sql)
  {
    try {
      dbi.withHandle(
          new HandleCallback<Void>()
          {
            @Override
            public Void withHandle(Handle handle) throws Exception
            {
              List<Map<String, Object>> table = handle.select(String.format("select * from SYS.SYSTABLES where tablename = \'%s\'", tableName.toUpperCase()));
              if (table.isEmpty()) {
                log.info("Creating table[%s]", tableName);
                handle.createStatement(sql).execute();
              } else {
                log.info("Table[%s] existed: [%s]", tableName, table);
              }
              return null;
            }
          }
      );
    }
    catch (Exception e) {
      log.warn(e, "Exception creating table");
    }
  }

  public void createIndex(final IDBI dbi, final String tableName, final String indexName, final String columnName) {
    dbi.withHandle(
        new HandleCallback<Void>()
        {
          @Override
          public Void withHandle(Handle handle) throws Exception
          {
            List<Map<String, Object>> table = handle.select(String.format("select * from SYS.SYSTABLES where tablename = \'%s\'", tableName.toUpperCase()));
            if (table.isEmpty()) {
              handle.createStatement(String.format("CREATE INDEX %1$s ON %2$s(%3$s)", indexName, tableName, columnName)).execute();
            }
            return null;
          }
        }
    );
  }

  public void createSegmentTable(final IDBI dbi, final String tableName)
  {
    createTable(
        dbi,
        tableName,
        String.format(
            "CREATE TABLE %1$s (\n"
            + "  id VARCHAR(255) NOT NULL,\n"
            + "  dataSource VARCHAR(255) NOT NULL,\n"
            + "  created_date VARCHAR(255) NOT NULL,\n"
            + "  start VARCHAR(255) NOT NULL,\n"
            + "  \"end\" VARCHAR(255) NOT NULL,\n"
            + "  partitioned BOOLEAN NOT NULL,\n"
            + "  version VARCHAR(255) NOT NULL,\n"
            + "  used BOOLEAN NOT NULL,\n"
            + "  payload %2$s NOT NULL,\n"
            + "  PRIMARY KEY (id)\n"
            + ");\n"
            + "CREATE INDEX idx_%1$s_datasource ON %1$s(dataSource);\n"
            + "CREATE INDEX idx_%1$s_used ON %1$s(used);",
            tableName, getPayloadType()
        )
    );
  }

  public void createRulesTable(final IDBI dbi, final String tableName)
  {
    createTable(
        dbi,
        tableName,
        String.format(
            "CREATE TABLE %1$s (\n"
            + "  id VARCHAR(255) NOT NULL,\n"
            + "  dataSource VARCHAR(255) NOT NULL,\n"
            + "  version VARCHAR(255) NOT NULL,\n"
            + "  payload %2$s NOT NULL,\n"
            + "  PRIMARY KEY (id)\n"
            + ");\n"
            + "CREATE INDEX idx_%1$s_datasource ON %1$s(dataSource);",
            tableName, getPayloadType()
        )
    );
    createIndex(dbi, tableName, "rules_dataSource", "dataSource");
  }

  public void createConfigTable(final IDBI dbi, final String tableName)
  {
    createTable(
        dbi,
        tableName,
        String.format(
            "CREATE TABLE %1$s (\n"
            + "  name VARCHAR(255) NOT NULL,\n"
            + "  payload %2$s NOT NULL,\n"
            + "  PRIMARY KEY(name)\n"
            + ");",
            tableName, getPayloadType()
        )
    );
  }

  public void createTaskTable(final IDBI dbi, final String tableName)
  {
    createTable(
        dbi,
        tableName,
        String.format(
            "CREATE TABLE %1$s (\n"
            + "  id VARCHAR(255) NOT NULL,\n"
            + "  created_date VARCHAR(255) NOT NULL,\n"
            + "  datasource VARCHAR(255) NOT NULL,\n"
            + "  payload %2$s NOT NULL,\n"
            + "  status_payload %2$s NOT NULL,\n"
            + "  active BOOLEAN NOT NULL DEFAULT FALSE,\n"
            + "  PRIMARY KEY (id)\n"
            + ");\n"
            + "CREATE INDEX idx_%1$s_active_created_date ON %1$s(active, created_date);",
            tableName, getPayloadType()
        )
    );
  }

  public void createTaskLogTable(final IDBI dbi, final String tableName)
  {
    createTable(
        dbi,
        tableName,
        String.format(
            "CREATE TABLE %1$s (\n"
            + "  id %2$s NOT NULL,\n"
            + "  task_id VARCHAR(255) DEFAULT NULL,\n"
            + "  log_payload %3$s,\n"
            + "  PRIMARY KEY (id)\n"
            + ");\n"
            + "CREATE INDEX idx_%1$s_task_id ON %1$s(task_id);",
            tableName, getSerialType(), getPayloadType()
        )
    );
  }

  public void createTaskLockTable(final IDBI dbi, final String tableName)
  {
    createTable(
        dbi,
        tableName,
        String.format(
            "CREATE TABLE %1$s (\n"
            + "  id %2$s NOT NULL,\n"
            + "  task_id VARCHAR(255) DEFAULT NULL,\n"
            + "  lock_payload %3$s,\n"
            + "  PRIMARY KEY (id)\n"
            + ");\n"
            + "CREATE INDEX idx_%1$s_task_id ON %1$s(task_id);",
            tableName, getSerialType(), getPayloadType()
        )
    );
  }

  @Override
  public Void insertOrUpdate(
      final String tableName,
      final String keyColumn,
      final String valueColumn,
      final String key,
      final byte[] value
  ) throws Exception
  {
    return getDBI().withHandle(
        new HandleCallback<Void>()
        {
          @Override
          public Void withHandle(Handle handle) throws Exception
          {
            Connection conn = getDBI().open().getConnection();
            handle.begin();
            conn.setAutoCommit(false);
            List<Map<String, Object>> entry = handle.createQuery(
                String.format("SELECT * FROM %1$s WHERE %2$s=:key", tableName, keyColumn)
            ).list();
            if (entry == null || entry.isEmpty()) {
              handle.createStatement(String.format("INSERT INTO %1$s (%2$s, %3$s) VALUES (:key, :value)",
                                                   tableName, keyColumn, valueColumn))
                    .bind("key", key)
                    .bind("value", value)
                    .execute();
            } else {
              handle.createStatement(String.format("UPDATE %1$s SET %3$s=:value WHERE %2$s=:key",
                                                   tableName, keyColumn, valueColumn))
                    .bind("key", key)
                    .bind("value", value)
                    .execute();
            }
            conn.setAutoCommit(true);
            handle.commit();
            return null;
          }
        }
    );
  }

  public abstract DBI getDBI();

  @Override
  public void createSegmentTable() {
    if (config.get().isCreateTables()) {
      createSegmentTable(getDBI(), dbTables.get().getSegmentsTable());
    }
  }

  @Override
  public void createRulesTable() {
    if (config.get().isCreateTables()) {
      createRulesTable(getDBI(), dbTables.get().getRulesTable());
    }
  }

  @Override
  public void createConfigTable() {
    if (config.get().isCreateTables()) {
      createConfigTable(getDBI(), dbTables.get().getConfigTable());
    }
  }

  @Override
  public void createTaskTables() {
    if (config.get().isCreateTables()) {
      final MetadataStorageTablesConfig metadataStorageTablesConfig = dbTables.get();
      createTaskTable(getDBI(), metadataStorageTablesConfig.getTasksTable());
      createTaskLogTable(getDBI(), metadataStorageTablesConfig.getTaskLogTable());
      createTaskLockTable(getDBI(), metadataStorageTablesConfig.getTaskLockTable());
    }
  }

  @Override
  public byte[] lookup(
      final String tableName,
      final String keyColumn,
      final String valueColumn,
      final String key
  )
  {
    final String selectStatement = String.format("SELECT %s FROM %s WHERE %s = :key", valueColumn,
                                                 tableName, keyColumn);

    return getDBI().withHandle(
        new HandleCallback<byte[]>()
        {
          @Override
          public byte[] withHandle(Handle handle) throws Exception
          {
            List<byte[]> matched = handle.createQuery(selectStatement)
                                         .bind("key", key)
                                         .map(
                                             new ResultSetMapper<byte[]>()
                                             {
                                               @Override
                                               public byte[] map(int index, ResultSet r, StatementContext ctx)
                                                   throws SQLException
                                               {
                                                 return r.getBytes(valueColumn);
                                               }
                                             }
                                         ).list();

            if (matched.isEmpty()) {
              return null;
            }

            if (matched.size() > 1) {
              throw new ISE("Error! More than one matching entry[%d] found for [%s]?!", matched.size(), key);
            }

            return matched.get(0);
          }
        }
    );
  }

  public MetadataStorageConnectorConfig getConfig() { return config.get(); }

  protected DataSource getDatasource()
  {
    MetadataStorageConnectorConfig connectorConfig = getConfig();

    BasicDataSource dataSource = new BasicDataSource();
    dataSource.setUsername(connectorConfig.getUser());
    dataSource.setPassword(connectorConfig.getPassword());
    String uri = connectorConfig.getConnectURI();
    dataSource.setUrl(uri);

    if (connectorConfig.isUseValidationQuery()) {
      dataSource.setValidationQuery(connectorConfig.getValidationQuery());
      dataSource.setTestOnBorrow(true);
    }

    return dataSource;
  }

}
