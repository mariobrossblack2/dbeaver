package org.jkiss.dbeaver.ext.mysql.model;

import net.sf.jkiss.utils.CommonUtils;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.mysql.MySQLConstants;
import org.jkiss.dbeaver.model.anno.Property;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCConstants;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.impl.jdbc.api.ResultSetStatement;
import org.jkiss.dbeaver.model.impl.jdbc.cache.JDBCStructCache;
import org.jkiss.dbeaver.model.impl.meta.AbstractCatalog;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSProcedureColumnType;
import org.jkiss.dbeaver.model.struct.DBSStructureAssistant;
import org.jkiss.dbeaver.model.struct.DBSTablePath;

import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * GenericCatalog
 */
public class MySQLCatalog
    extends AbstractCatalog<MySQLDataSource>
    implements DBSStructureAssistant
{
    private String defaultCharset;
    private String defaultCollation;
    private String sqlPath;
    private TableCache tableCache = new TableCache();
    private ProceduresCache proceduresCache = new ProceduresCache();

    public MySQLCatalog(MySQLDataSource dataSource, String catalogName)
    {
        super(dataSource, catalogName);
    }

    TableCache getTableCache()
    {
        return tableCache;
    }

    ProceduresCache getProceduresCache()
    {
        return proceduresCache;
    }

    public String getDescription()
    {
        return null;
    }

    @Property(name = "Default Charset", viewable = true, order = 2)
    public String getDefaultCharset()
    {
        return defaultCharset;
    }

    void setDefaultCharset(String defaultCharset)
    {
        this.defaultCharset = defaultCharset;
    }

    public String getDefaultCollation()
    {
        return defaultCollation;
    }

    void setDefaultCollation(String defaultCollation)
    {
        this.defaultCollation = defaultCollation;
    }

    public String getSqlPath()
    {
        return sqlPath;
    }

    void setSqlPath(String sqlPath)
    {
        this.sqlPath = sqlPath;
    }

    public List<MySQLIndex> getIndexes(DBRProgressMonitor monitor)
        throws DBException
    {
        // Cache tables and columns
        tableCache.loadChildren(monitor, null);

        // Copy indexes from tables because we do not want
        // to place the same objects in different places of the tree model
        List<MySQLIndex> indexList = new ArrayList<MySQLIndex>();
        for (MySQLTable table : getTables(monitor)) {
            for (MySQLIndex index : table.getIndexes(monitor)) {
                indexList.add(new MySQLIndex(index));
            }
        }
        return indexList;
    }

    public List<MySQLTable> getTables(DBRProgressMonitor monitor)
        throws DBException
    {
        return tableCache.getObjects(monitor);
    }

    public MySQLTable getTable(DBRProgressMonitor monitor, String name)
        throws DBException
    {
        return tableCache.getObject(monitor, name);
    }

    public List<MySQLProcedure> getProcedures(DBRProgressMonitor monitor)
        throws DBException
    {
        return proceduresCache.getObjects(monitor);
    }

    public List<DBSTablePath> findTableNames(DBRProgressMonitor monitor, String tableMask, int maxResults) throws DBException
    {
        return getDataSource().findTableNames(monitor, tableMask, maxResults);
    }

    public Collection<MySQLTable> getChildren(DBRProgressMonitor monitor)
        throws DBException
    {
        return getTables(monitor);
    }

    public MySQLTable getChild(DBRProgressMonitor monitor, String childName)
        throws DBException
    {
        return getTable(monitor, childName);
    }

    public void cacheStructure(DBRProgressMonitor monitor, int scope)
        throws DBException
    {
        tableCache.loadObjects(monitor);
        if ((scope & STRUCT_ATTRIBUTES) != 0) {
            tableCache.loadChildren(monitor, null);
        }
    }

    @Override
    public boolean refreshObject(DBRProgressMonitor monitor)
        throws DBException
    {
        super.refreshObject(monitor);
        tableCache.clearCache();
        proceduresCache.clearCache();
        return true;
    }

    class TableCache extends JDBCStructCache<MySQLTable, MySQLTableColumn> {
        
        protected TableCache()
        {
            super("tables", "columns", JDBCConstants.TABLE_NAME);
        }

        protected PreparedStatement prepareObjectsStatement(DBRProgressMonitor monitor)
            throws SQLException, DBException
        {
            PreparedStatement dbStat = getDataSource().getConnection().prepareStatement(
                MySQLConstants.QUERY_SELECT_TABLES);
            dbStat.setString(1, getName());
            return dbStat;
        }

        protected MySQLTable fetchObject(DBRProgressMonitor monitor, ResultSet dbResult)
            throws SQLException, DBException
        {
            return new MySQLTable(MySQLCatalog.this, dbResult);
        }

        protected boolean isChildrenCached(MySQLTable table)
        {
            return table.isColumnsCached();
        }

        protected void cacheChildren(MySQLTable table, List<MySQLTableColumn> columns)
        {
            table.setColumns(columns);
        }

        protected PreparedStatement prepareChildrenStatement(DBRProgressMonitor monitor, MySQLTable forTable)
            throws SQLException, DBException
        {
            StringBuilder sql = new StringBuilder();
            sql
                .append("SELECT * FROM ").append(MySQLConstants.META_TABLE_COLUMNS)
                .append(" WHERE ").append(MySQLConstants.COL_TABLE_SCHEMA).append("=?");
            if (forTable != null) {
                sql.append(" AND ").append(MySQLConstants.COL_TABLE_NAME).append("=?");
            }
            sql.append(" ORDER BY ").append(MySQLConstants.COL_ORDINAL_POSITION);

            PreparedStatement dbStat = getDataSource().getConnection().prepareStatement(sql.toString());
            dbStat.setString(1, MySQLCatalog.this.getName());
            if (forTable != null) {
                dbStat.setString(2, forTable.getName());
            }
            return dbStat;
        }

        protected MySQLTableColumn fetchChild(DBRProgressMonitor monitor, MySQLTable table, ResultSet dbResult)
            throws SQLException, DBException
        {
            return new MySQLTableColumn(table, dbResult);
        }
    }

    /**
     * Procedures cache implementation
     */
    class ProceduresCache extends JDBCStructCache<MySQLProcedure, MySQLProcedureColumn> {

        ProceduresCache()
        {
            super("procedures", "procedure columns", JDBCConstants.PROCEDURE_NAME);
        }

        protected PreparedStatement prepareObjectsStatement(DBRProgressMonitor monitor)
            throws SQLException, DBException
        {
            PreparedStatement dbStat = getDataSource().getConnection().prepareStatement(
                MySQLConstants.QUERY_SELECT_ROUTINES);
            dbStat.setString(1, getName());
            return dbStat;
        }

        protected MySQLProcedure fetchObject(DBRProgressMonitor monitor, ResultSet dbResult)
            throws SQLException, DBException
        {
            return new MySQLProcedure(MySQLCatalog.this, dbResult);
        }

        protected boolean isChildrenCached(MySQLProcedure parent)
        {
            return parent.isColumnsCached();
        }

        protected void cacheChildren(MySQLProcedure parent, List<MySQLProcedureColumn> columns)
        {
            parent.cacheColumns(columns);
        }

        protected PreparedStatement prepareChildrenStatement(DBRProgressMonitor monitor, MySQLProcedure procedure)
            throws SQLException, DBException
        {
            // Load procedure columns thru MySQL metadata
            // There is no metadata table about proc/func columns -
            // it should be parsed from SHOW CREATE PROCEDURE/FUNCTION query
            // Lets driver do it instead of me
            return new ResultSetStatement(
                getDataSource().getConnection().getMetaData().getProcedureColumns(
                    getName(),
                    null,
                    procedure.getName(),
                    null));
        }

        protected MySQLProcedureColumn fetchChild(DBRProgressMonitor monitor, MySQLProcedure parent, ResultSet dbResult)
            throws SQLException, DBException
        {
            String columnName = JDBCUtils.safeGetString(dbResult, JDBCConstants.COLUMN_NAME);
            int columnTypeNum = JDBCUtils.safeGetInt(dbResult, JDBCConstants.COLUMN_TYPE);
            int valueType = JDBCUtils.safeGetInt(dbResult, JDBCConstants.DATA_TYPE);
            String typeName = JDBCUtils.safeGetString(dbResult, JDBCConstants.TYPE_NAME);
            int position = JDBCUtils.safeGetInt(dbResult, JDBCConstants.ORDINAL_POSITION);
            int columnSize = JDBCUtils.safeGetInt(dbResult, JDBCConstants.LENGTH);
            boolean isNullable = JDBCUtils.safeGetInt(dbResult, JDBCConstants.NULLABLE) != DatabaseMetaData.procedureNoNulls;
            int scale = JDBCUtils.safeGetInt(dbResult, JDBCConstants.SCALE);
            int precision = JDBCUtils.safeGetInt(dbResult, JDBCConstants.PRECISION);
            int radix = JDBCUtils.safeGetInt(dbResult, JDBCConstants.RADIX);
            String remarks = JDBCUtils.safeGetString(dbResult, JDBCConstants.REMARKS);
            //DBSDataType dataType = getDataSource().getInfo().getSupportedDataType(typeName);
            DBSProcedureColumnType columnType;
            switch (columnTypeNum) {
                case DatabaseMetaData.procedureColumnIn: columnType = DBSProcedureColumnType.IN; break;
                case DatabaseMetaData.procedureColumnInOut: columnType = DBSProcedureColumnType.INOUT; break;
                case DatabaseMetaData.procedureColumnOut: columnType = DBSProcedureColumnType.OUT; break;
                case DatabaseMetaData.procedureColumnReturn: columnType = DBSProcedureColumnType.RETURN; break;
                case DatabaseMetaData.procedureColumnResult: columnType = DBSProcedureColumnType.RESULTSET; break;
                default: columnType = DBSProcedureColumnType.UNKNOWN; break;
            }
            if (CommonUtils.isEmpty(columnName) && columnType == DBSProcedureColumnType.RETURN) {
                columnName = "RETURN";
            }
            return new MySQLProcedureColumn(
                parent,
                columnName,
                typeName,
                valueType,
                position,
                columnSize,
                scale, precision, radix, isNullable,
                remarks,
                columnType);
        }
    }

}
