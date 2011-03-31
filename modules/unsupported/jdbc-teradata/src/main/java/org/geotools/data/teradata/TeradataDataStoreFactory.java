/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2002-2008, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.data.teradata;

import org.geotools.jdbc.*;

import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TeradataDataStoreFactory extends JDBCDataStoreFactory {

    /**
     * parameter for database type
     */
    public static final Param DBTYPE = new Param("dbtype", String.class, "Type", true, "teradata");

    /**
     * enables using && in bbox queries
     */
    public static final Param LOOSEBBOX = new Param("Loose bbox", Boolean.class, "Perform only primary filter on bbox", false, Boolean.TRUE);

    /**
     * parameter that enables estimated extends instead of exact ones
     */
    public static final Param ESTIMATED_EXTENTS = new Param("Estimated extends", Boolean.class, "Use the spatial index information to quickly get an estimate of the data bounds", false, Boolean.TRUE);

    /**
     * parameter for database port
     */
    public static final Param PORT = new Param("port", Integer.class, "Port", true, 1025);

    public static final Param TMODE = new Param("tmode", String.class, "tmode", false, "ANSI");

    public static final Param CHARSET = new Param("charset", String.class, "charset", false, "UTF8");

    private static final PrimaryKeyFinder KEY_FINDER = new CompositePrimaryKeyFinder(
            new MetadataTablePrimaryKeyFinder(),
            new TeradataPrimaryKeyFinder(),
            new HeuristicPrimaryKeyFinder());

    /**
     * parameter for database schema
     */
//    public static final Param SCHEMA = new Param("schema", String.class, "Schema", false, "public");

    // TODO rest of parameters for connection (ACCOUNT, Charset, etc...)
    @Override
    protected SQLDialect createSQLDialect(JDBCDataStore dataStore) {
        return new TeradataGISDialect(dataStore);
    }

    @Override
    protected String getDatabaseID() {
        return (String) DBTYPE.sample;
    }

    @Override
    public String getDisplayName() {
        return "Teradata";
    }

    public String getDescription() {
        return "Teradata Database";
    }

    @Override
    protected String getDriverClassName() {
        return "com.teradata.jdbc.TeraDriver";
    }

    @Override
    protected boolean checkDBType(Map params) {
        return checkDBType(params, "teradata");
    }

    protected JDBCDataStore createDataStoreInternal(JDBCDataStore dataStore, Map params)
            throws IOException {

        // setup loose bbox
        TeradataGISDialect dialect = (TeradataGISDialect) dataStore.getSQLDialect();
        Boolean loose = (Boolean) LOOSEBBOX.lookUp(params);
        dialect.setLooseBBOXEnabled(loose == null || Boolean.TRUE.equals(loose));

        // check the estimated extents
        Boolean estimated = (Boolean) ESTIMATED_EXTENTS.lookUp(params);
        dialect.setEstimatedExtentsEnabled(estimated == null || Boolean.TRUE.equals(estimated));

        if (!params.containsKey(PK_METADATA_TABLE.key))
            dataStore.setPrimaryKeyFinder(KEY_FINDER);
        return dataStore;
    }

    @Override
    protected void setupParameters(Map parameters) {
        // NOTE: when adding parameters here remember to add them to TeradataJNDIDataStoreFactory

        super.setupParameters(parameters);
        parameters.put(DBTYPE.key, DBTYPE);
//        parameters.put(SCHEMA.key, SCHEMA);
        parameters.put(LOOSEBBOX.key, LOOSEBBOX);
        parameters.put(ESTIMATED_EXTENTS.key, ESTIMATED_EXTENTS);
        parameters.put(PORT.key, PORT);
        parameters.put(MAX_OPEN_PREPARED_STATEMENTS.key, MAX_OPEN_PREPARED_STATEMENTS);
    }

    @Override
    protected String getValidationQuery() {
        return "select now()";
    }

    @Override
    protected String getJDBCUrl(Map params) throws IOException {
        String host = (String) HOST.lookUp(params);
        String db = (String) DATABASE.lookUp(params);
        int port = (Integer) PORT.lookUp(params);
        String mode = (String) TMODE.lookUp(params);
        if (mode == null)
            mode = TMODE.sample.toString();
        String charset = (String) CHARSET.lookUp(params);
        if (charset == null)
            charset = CHARSET.sample.toString();
        return "jdbc:teradata://" + host + "/DATABASE=" + db + ",PORT=" + port + ",TMODE=" + mode + ",CHARSET=" + charset;
    }

    /**
     * The Terradata Key Finder
     *
     * @author Stéphane Brunner @ camptocamp
     */
    private static class TeradataPrimaryKeyFinder extends PrimaryKeyFinder {

        @Override
        public PrimaryKey getPrimaryKey(JDBCDataStore store, String schema,
                                        String table, Connection cx) throws SQLException {

            List<PrimaryKeyColumn> columns = tryForPrimaryKey(schema, table, cx);
            if (columns.isEmpty()) {
                columns = tryForSequence(schema, table, cx);
            }

            if (columns.isEmpty()) {
                return null;
            } else {
                return new PrimaryKey(table, columns);
            }
        }

        private List<PrimaryKeyColumn> tryForSequence(String schema, String table, Connection cx) throws SQLException {
            List<PrimaryKeyColumn> columns = new ArrayList<PrimaryKeyColumn>();
            StringBuilder sql = new StringBuilder("SELECT ColumnName FROM DBC.columnsX WHERE ");
            if (schema != null) {
                sql.append("DatabaseName = '").append(schema).append("' AND ");
            }
            sql.append("TableName = '").append(table).append("' AND (IdColType='GA' or IdColType='GD')");
            Statement st = cx.createStatement();
            java.sql.ResultSet result = st.executeQuery(sql.toString());
            boolean next = result.next();
            try {
                TableMetadata tableMetadata = new TableMetadata(st, schema, table);

                while (next) {
                    String columnName = result.getString("ColumnName").trim();
                    int ordinal = tableMetadata.ordinal(columnName);
                    Class<?> columnClass = tableMetadata.columnClass(ordinal);
                    if (tableMetadata.isAutoIncrement(ordinal)) {
                        columns.add(new AutoGeneratedPrimaryKeyColumn(columnName, columnClass));
                    }
                    next = result.next();
                }
            } finally {
                st.close();
            }

            return columns;
        }

        private List<PrimaryKeyColumn> tryForSequence1(String schema, String table, Connection cx) throws SQLException {
            List<PrimaryKeyColumn> columns = new ArrayList<PrimaryKeyColumn>();
            StringBuilder sql = new StringBuilder("select ColumnName from dbc.indices WHERE ");
            if (schema != null) {
                sql.append("DatabaseName = '").append(schema).append("' AND ");
            }
            sql.append("TableName = '").append(table).append("' AND IndexType = 'P'");
            Statement st = cx.createStatement();
            java.sql.ResultSet result = st.executeQuery(sql.toString());
            boolean next = result.next();
            try {
                TableMetadata tableMetadata = new TableMetadata(st, schema, table);

                while (next) {
                    String columnName = result.getString("ColumnName").trim();
                    int ordinal = tableMetadata.ordinal(columnName);
                    Class<?> columnClass = tableMetadata.columnClass(ordinal);
                    if (tableMetadata.isAutoIncrement(ordinal)) {
                        columns.add(new AutoGeneratedPrimaryKeyColumn(columnName, columnClass));
                    }
                    next = result.next();
                }
            } finally {
                st.close();
            }

            return columns;
        }

        private List<PrimaryKeyColumn> tryForPrimaryKey(String schema, String table, Connection cx) throws SQLException {
            List<PrimaryKeyColumn> columns = new ArrayList<PrimaryKeyColumn>();
            ResultSet md = cx.getMetaData().getPrimaryKeys(null, schema, table);
            boolean next = md.next();
            if (next) {
                Statement stmt = cx.createStatement();
                try {
                    TableMetadata tableMetadata = new TableMetadata(stmt, schema, table);
                    while (next) {
                        String columnName = md.getString("COLUMN_NAME").trim();
                        int ordinal = tableMetadata.ordinal(columnName);
                        Class<?> columnClass = tableMetadata.columnClass(ordinal);
                        if (tableMetadata.isAutoIncrement(ordinal)) {
                            columns.add(new AutoGeneratedPrimaryKeyColumn(columnName, columnClass));
                        } else {
                            columns.add(new NonIncrementingPrimaryKeyColumn(columnName, columnClass));
                        }
                        next = md.next();
                    }
                } finally {
                    stmt.close();
                }
            }
            return columns;
        }
    }

    private static class TableMetadata {
        final Statement stmt;
        final ResultSet resultSet;
        final ResultSetMetaData tableMetadata;

        private TableMetadata(Statement stmt, String schema, String table) throws SQLException {
            this.stmt = stmt;
            String from = "\"" + table + "\"";
            if (schema != null) {
                from = "\"" + schema + "\"." + from;
            }
            resultSet = stmt.executeQuery("select * from " + from + " where 1=2");
            tableMetadata = resultSet.getMetaData();
        }


        public int ordinal(String columnName) throws SQLException {
            return resultSet.findColumn(columnName);
        }

        public Class<?> columnClass(int ordinal) throws SQLException {
            try {
                return Thread.currentThread().getContextClassLoader().loadClass(tableMetadata.getColumnClassName(ordinal));
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        public boolean isAutoIncrement(int ordinal) throws SQLException {
            return tableMetadata.isAutoIncrement(ordinal);
        }
    }
}