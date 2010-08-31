/*
 * Copyright (c) 2010, Serge Rieder and others. All Rights Reserved.
 */

package org.jkiss.dbeaver.ext.erd.command;

import org.eclipse.draw2d.AbsoluteBendpoint;
import org.eclipse.draw2d.geometry.Point;
import org.eclipse.gef.commands.Command;
import org.jkiss.dbeaver.ext.erd.part.AssociationPart;


public abstract class BendpointCommand extends Command {

    protected final AssociationPart association;

    public BendpointCommand(AssociationPart association) {
        this.association = association;
    }

}