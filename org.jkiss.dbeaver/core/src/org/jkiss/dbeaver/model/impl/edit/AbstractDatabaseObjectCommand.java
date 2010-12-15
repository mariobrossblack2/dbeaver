/*
 * Copyright (c) 2010, Serge Rieder and others. All Rights Reserved.
 */

package org.jkiss.dbeaver.model.impl.edit;

import org.eclipse.swt.graphics.Image;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.IDatabaseObjectCommand;
import org.jkiss.dbeaver.ext.IDatabasePersistAction;
import org.jkiss.dbeaver.model.struct.DBSObject;

import java.util.Map;

/**
 * Abstract object command
 */
public abstract class AbstractDatabaseObjectCommand<OBJECT_TYPE extends DBSObject> implements IDatabaseObjectCommand<OBJECT_TYPE> {
    private final String title;
    private final Image icon;

    protected AbstractDatabaseObjectCommand(String title, Image icon)
    {
        this.title = title;
        this.icon = icon;
    }

    protected AbstractDatabaseObjectCommand(String title)
    {
        this(title, null);
    }

    public String getTitle()
    {
        return title;
    }

    public Image getIcon()
    {
        return icon;
    }

    public boolean isUndoable()
    {
        return true;
    }

    public void validateCommand(OBJECT_TYPE object) throws DBException
    {
        // do nothing by default
    }

    public void updateModel(OBJECT_TYPE object)
    {
    }

    public IDatabaseObjectCommand<OBJECT_TYPE> merge(IDatabaseObjectCommand<OBJECT_TYPE> prevCommand, Map<String, Object> userParams)
    {
        return this;
    }

    public IDatabasePersistAction[] getPersistActions(OBJECT_TYPE object)
    {
        return null;
    }

}
