/*
 * Copyright (c) 2011, Serge Rieder and others. All Rights Reserved.
 */

/*
 * Created on Jul 15, 2004
 */
package org.jkiss.dbeaver.ext.erd.policy;

import org.eclipse.draw2d.geometry.Point;
import org.eclipse.gef.EditPart;
import org.eclipse.gef.Request;
import org.eclipse.gef.commands.Command;
import org.eclipse.gef.editpolicies.AbstractEditPolicy;
import org.eclipse.gef.editpolicies.ContainerEditPolicy;
import org.eclipse.gef.requests.CreateRequest;
import org.eclipse.gef.requests.GroupRequest;
import org.jkiss.dbeaver.ext.erd.command.EntityAddCommand;
import org.jkiss.dbeaver.ext.erd.model.ERDTable;
import org.jkiss.dbeaver.ext.erd.model.EntityDiagram;
import org.jkiss.dbeaver.ext.erd.part.DiagramPart;

import java.util.Collection;
import java.util.Collections;

/**
 * Handles creation of new tables using drag and drop or point and click from the palette
 *
 * @author Serge Rieder
 */
public class DiagramContainerEditPolicy extends ContainerEditPolicy {

    /**
     * @see org.eclipse.gef.editpolicies.ContainerEditPolicy#getAddCommand(org.eclipse.gef.requests.GroupRequest)
     */
    protected Command getAddCommand(GroupRequest request)
    {
        EditPart host = getTargetEditPart(request);
        return null;
    }

    /**
     * @see ContainerEditPolicy#getCreateCommand(org.eclipse.gef.requests.CreateRequest)
     */
    protected Command getCreateCommand(CreateRequest request)
    {
        Collection<ERDTable> tables;
        Object newObject = request.getNewObject();
        if (newObject instanceof ERDTable) {
            tables = Collections.singletonList((ERDTable) newObject);
        } else if (newObject instanceof Collection) {
            tables = (Collection<ERDTable>) newObject;
        } else {
            return null;
        }

        Point location = request.getLocation();
        //EditPart host = getTargetEditPart(request);

        DiagramPart diagramPart = (DiagramPart) getHost();
        EntityDiagram entityDiagram = diagramPart.getDiagram();

        EntityAddCommand entityAddCommand = new EntityAddCommand();
        entityAddCommand.setDiagram(entityDiagram);
        entityAddCommand.setTables(tables);
        entityAddCommand.setLocation(location);

        return entityAddCommand;
    }

    /**
     * @see AbstractEditPolicy#getTargetEditPart(org.eclipse.gef.Request)
     */
    public EditPart getTargetEditPart(Request request)
    {
        if (REQ_CREATE.equals(request.getType())) {
            return getHost();
        }
        if (REQ_ADD.equals(request.getType())) {
            return getHost();
        }
        if (REQ_MOVE.equals(request.getType())) {
            return getHost();
        }
        return super.getTargetEditPart(request);
    }

}