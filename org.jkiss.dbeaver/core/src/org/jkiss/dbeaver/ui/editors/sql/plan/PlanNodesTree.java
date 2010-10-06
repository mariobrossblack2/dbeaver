/*
 * Copyright (c) 2010, Serge Rieder and others. All Rights Reserved.
 */

package org.jkiss.dbeaver.ui.editors.sql.plan;

import net.sf.jkiss.utils.CommonUtils;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IWorkbenchPart;
import org.jkiss.dbeaver.ext.IDataSourceProvider;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.plan.DBCQueryPlanner;
import org.jkiss.dbeaver.model.exec.plan.DBCPlan;
import org.jkiss.dbeaver.model.exec.plan.DBCPlanNode;
import org.jkiss.dbeaver.runtime.load.DatabaseLoadService;
import org.jkiss.dbeaver.runtime.load.LoadingUtils;
import org.jkiss.dbeaver.ui.controls.itemlist.ObjectListControl;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;

/**
 * Plan nodes tree
 */
public class PlanNodesTree extends ObjectListControl<DBCPlanNode> {

    private static ITreeContentProvider CONTENT_PROVIDER = new ITreeContentProvider() {
        public Object[] getElements(Object inputElement)
        {
            if (inputElement instanceof Collection) {
                return ((Collection<?>)inputElement).toArray();
            }
            return null;
        }

        public Object[] getChildren(Object parentElement)
        {
            if (parentElement instanceof DBCPlanNode) {
                Collection<? extends DBCPlanNode> nestedNodes = ((DBCPlanNode) parentElement).getNested();
                return CommonUtils.isEmpty(nestedNodes) ? new Object[0] : nestedNodes.toArray();
            }
            return null;
        }

        public Object getParent(Object element)
        {
            if (element instanceof DBCPlanNode) {
                return ((DBCPlanNode)element).getParent();
            }
            return null;
        }

        public boolean hasChildren(Object element)
        {
            return element instanceof DBCPlanNode && !CommonUtils.isEmpty(((DBCPlanNode) element).getNested());
        }

        public void dispose()
        {
        }

        public void inputChanged(Viewer viewer, Object oldInput, Object newInput)
        {
        }

    };

    private IDataSourceProvider dataSourceProvider;

    public PlanNodesTree(Composite parent, int style, IWorkbenchPart workbenchPart, IDataSourceProvider dataSourceProvider)
    {
        super(parent, style, workbenchPart, CONTENT_PROVIDER);
        this.dataSourceProvider = dataSourceProvider;
        setShowName(false);
        setFitWidth(true);
    }

    @Override
    protected DBPDataSource getDataSource()
    {
        return dataSourceProvider.getDataSource();
    }

    @Override
    protected Object getObjectValue(DBCPlanNode item)
    {
        return item;
    }

    @Override
    protected String getObjectLabel(DBCPlanNode item)
    {
        return null;
    }

    @Override
    protected Image getObjectImage(DBCPlanNode item)
    {
        return null;
    }

    @Override
    public void clearData()
    {
        super.clearData();
        createColumn("", "", null);
    }

    public void fillData(DBCQueryPlanner planner, String query)
    {
        super.clearData();
        super.loadData(
            LoadingUtils.executeService(
                new ExplainPlanService(planner, query),
                new ObjectsLoadVisualizer()));
    }

    private class ExplainPlanService extends DatabaseLoadService<Collection<DBCPlanNode>> {

        private DBCQueryPlanner planner;
        private String query;

        protected ExplainPlanService(DBCQueryPlanner planner, String query)
        {
            super("Explain plan", getDataSource());
            this.planner = planner;
            this.query = query;
        }

        public Collection<DBCPlanNode> evaluate()
            throws InvocationTargetException, InterruptedException
        {
            try {
                if (getDataSource() == null) {
                    throw new DBCException("No database connection");
                }

                DBCExecutionContext context = getDataSource().openContext(getProgressMonitor(), DBCExecutionPurpose.UTIL, "Explain '" + query + "'");
                try {
                    DBCPlan plan = planner.planQueryExecution(context, query);
                    return plan.getPlanNodes();
                }
                finally {
                    context.close();
                }
            } catch (Throwable ex) {
                if (ex instanceof InvocationTargetException) {
                    throw (InvocationTargetException)ex;
                } else {
                    throw new InvocationTargetException(ex);
                }
            }
        }
    }

}
