/*
 * Copyright (C) 2010-2014 Serge Rieder
 * serge@jkiss.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package org.jkiss.dbeaver.model.exec;

import org.eclipse.swt.graphics.Image;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.ui.IObjectImageProvider;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDPseudoAttribute;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSAttributeBase;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSEntityReferrer;

import java.util.List;

/**
 * Result set attribute meta data
 */
public class DBCNestedAttributeMetaData implements DBCAttributeMetaData, IObjectImageProvider
{
    private final DBSEntityAttribute attribute;
    private final int index;
    private final DBCAttributeMetaData parentMeta;

    public DBCNestedAttributeMetaData(DBSEntityAttribute attribute, int index, DBCAttributeMetaData parentMeta) {
        this.attribute = attribute;
        this.index = index;
        this.parentMeta = parentMeta;
    }

    @Override
    public int getIndex() {
        return index;
    }

    @NotNull
    @Override
    public String getLabel() {
        return attribute.getName();
    }

    @Nullable
    @Override
    public String getEntityName() {
        return parentMeta.getEntityName();
    }

    @Override
    public boolean isReadOnly() {
        return parentMeta.isReadOnly();
    }

    @Nullable
    @Override
    public DBDPseudoAttribute getPseudoAttribute() {
        return null;
    }

    @Override
    public void setPseudoAttribute(DBDPseudoAttribute pseudoAttribute) {

    }

    @Nullable
    @Override
    public DBSEntityAttribute getAttribute(DBRProgressMonitor monitor) throws DBException {
        return attribute;
    }

    @Nullable
    @Override
    public DBCEntityMetaData getEntity() {
        return parentMeta.getEntity();
    }

    @Override
    public boolean isReference(DBRProgressMonitor monitor) throws DBException {
        return false;
    }

    @Override
    public List<DBSEntityReferrer> getReferrers(DBRProgressMonitor monitor) throws DBException {
        return null;
    }

    @Override
    public boolean isRequired() {
        return attribute.isRequired();
    }

    @Override
    public boolean isAutoGenerated() {
        return attribute.isAutoGenerated();
    }

    @Override
    public boolean isPseudoAttribute() {
        return false;
    }

    @Override
    public String getName() {
        return attribute.getName();
    }

    @Override
    public String getTypeName() {
        return attribute.getTypeName();
    }

    @Override
    public int getTypeID() {
        return attribute.getTypeID();
    }

    @Override
    public DBPDataKind getDataKind() {
        return attribute.getDataKind();
    }

    @Override
    public int getScale() {
        return attribute.getScale();
    }

    @Override
    public int getPrecision() {
        return attribute.getPrecision();
    }

    @Override
    public long getMaxLength() {
        return attribute.getMaxLength();
    }

    @Nullable
    @Override
    public Image getObjectImage() {
        if (attribute instanceof IObjectImageProvider) {
            return ((IObjectImageProvider) attribute).getObjectImage();
        }
        return DBUtils.getDataIcon(this).getImage();
    }
}
