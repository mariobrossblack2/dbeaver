/*
 * Copyright (c) 2010, Serge Rieder and others. All Rights Reserved.
 */

package org.jkiss.dbeaver.model.meta;

import org.jkiss.dbeaver.model.struct.DBSFolder;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.registry.tree.DBXTreeFolder;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ui.actions.OpenObjectEditorAction;
import org.eclipse.ui.IActionDelegate;

/**
 * DBMTreeFolder
 */
public class DBMTreeFolder extends DBMTreeNode implements DBSFolder
{
    private DBXTreeFolder meta;

    DBMTreeFolder(DBMNode parent, DBXTreeFolder meta)
    {
        super(parent);
        this.meta = meta;
        if (this.getModel() != null) {
            this.getModel().addNode(this, this);
        }
    }

    protected void dispose()
    {
        if (this.getModel() != null) {
            this.getModel().removeNode(this);
        }
        this.meta = null;
        super.dispose();
    }

    public DBXTreeFolder getMeta()
    {
        return meta;
    }

    public DBSObject getObject()
    {
        return this;
    }

    public Object getValueObject()
    {
        return getParentNode() == null ? null : getParentNode().getValueObject();
    }

    public String getName()
    {
        return meta.getLabel();
    }

    public String getDescription()
    {
        return meta.getDescription();
    }

    public DBSObject getParentObject()
    {
        return getParentNode().getObject();
    }

    public DBPDataSource getDataSource()
    {
        return getParentObject().getDataSource();
    }

    public boolean refreshObject()
        throws DBException
    {
        return false;
    }

    public DBMNode refreshNode(DBRProgressMonitor monitor)
        throws DBException
    {
        return this.getParentNode().refreshNode(monitor);
    }

    public IActionDelegate getDefaultAction()
    {
        return new OpenObjectEditorAction();
    }

    public String getItemsType()
    {
        return meta.getType();
    }

    public Class<?> getItemsClass()
    {
        try {
            return Class.forName(getItemsType());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

}
