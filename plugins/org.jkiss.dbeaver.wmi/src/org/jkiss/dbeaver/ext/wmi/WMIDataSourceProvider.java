/*
 * Copyright (c) 2012, Serge Rieder and others. All Rights Reserved.
 */

package org.jkiss.dbeaver.ext.wmi;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.ui.views.properties.IPropertyDescriptor;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.wmi.model.WMIDataSource;
import org.jkiss.dbeaver.model.*;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDataSourceContainer;
import org.jkiss.wmi.service.WMIService;

import java.util.Collection;

public class WMIDataSourceProvider implements DBPDataSourceProvider {


    private boolean libLoaded = false;

    public void init(DBPApplication application)
    {
    }

    public long getFeatures()
    {
        return FEATURE_SCHEMAS;
    }

    public Collection<IPropertyDescriptor> getConnectionProperties(DBPDriver driver, DBPConnectionInfo connectionInfo) throws DBException
    {
        return null;
    }

    public DBPDataSource openDataSource(DBRProgressMonitor monitor, DBSDataSourceContainer container) throws DBException
    {
        return new WMIDataSource(container);
    }

    public void close()
    {
        //WMIService.unInitializeThread();
    }

}
