/*
 * Copyright (c) 2011, Serge Rieder and others. All Rights Reserved.
 */

package org.jkiss.dbeaver.ui.editors.entity;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.*;
import org.eclipse.ui.views.properties.IPropertySheetPage;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.core.DBeaverCore;
import org.jkiss.dbeaver.ext.IDatabasePersistAction;
import org.jkiss.dbeaver.ext.ui.IFolderListener;
import org.jkiss.dbeaver.ext.ui.IFolderedPart;
import org.jkiss.dbeaver.ext.ui.INavigatorModelView;
import org.jkiss.dbeaver.ext.ui.IRefreshablePart;
import org.jkiss.dbeaver.model.DBPObject;
import org.jkiss.dbeaver.model.edit.DBECommand;
import org.jkiss.dbeaver.model.edit.DBECommandContext;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.impl.edit.DBECommandAdapter;
import org.jkiss.dbeaver.model.navigator.DBNDataSource;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DBRRunnableWithProgress;
import org.jkiss.dbeaver.model.struct.DBSDataSourceContainer;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectStateful;
import org.jkiss.dbeaver.registry.EntityEditorDescriptor;
import org.jkiss.dbeaver.registry.EntityEditorsRegistry;
import org.jkiss.dbeaver.registry.tree.DBXTreeItem;
import org.jkiss.dbeaver.registry.tree.DBXTreeNode;
import org.jkiss.dbeaver.runtime.DefaultProgressMonitor;
import org.jkiss.dbeaver.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.ui.DBIcon;
import org.jkiss.dbeaver.ui.help.IHelpContextIds;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.ConfirmationDialog;
import org.jkiss.dbeaver.ui.dialogs.ViewSQLDialog;
import org.jkiss.dbeaver.ui.editors.MultiPageDatabaseEditor;
import org.jkiss.dbeaver.ui.preferences.PrefConstants;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

/**
 * EntityEditor
 */
public class EntityEditor extends MultiPageDatabaseEditor implements INavigatorModelView, ISaveablePart2, IFolderedPart
{
    static final Log log = LogFactory.getLog(EntityEditor.class);
    private boolean hasPropertiesEditor;

    private static class EditorDefaults {
        String pageId;
        String folderId;

        private EditorDefaults(String pageId, String folderId)
        {
            this.pageId = pageId;
            this.folderId = folderId;
        }
    }

    private static final Map<String, EditorDefaults> defaultPageMap = new HashMap<String, EditorDefaults>();

    private final Map<String, IEditorPart> editorMap = new LinkedHashMap<String, IEditorPart>();
    private IEditorPart activeEditor;
    private DBECommandAdapter commandListener;
    private IFolderListener folderListener;

    public EntityEditor()
    {
        folderListener = new IFolderListener() {
            public void folderSelected(String folderId)
            {
                IEditorPart editor = getActiveEditor();
                if (editor != null) {
                    String editorPageId = getEditorPageId(editor);
                    if (editorPageId != null) {
                        updateEditorDefaults(editorPageId, folderId);
                    }
                }
            }
        };
    }

    public DBSObject getDatabaseObject()
    {
        return getEditorInput().getDatabaseObject();
    }

    public DBECommandContext getCommandContext()
    {
        return getEditorInput().getCommandContext();
    }

    @Override
    public void dispose()
    {
        //final DBPDataSource dataSource = getDataSource();

//        if (getCommandContext() != null && getCommandContext().isDirty()) {
//            getCommandContext().resetChanges();
//        }
        if (commandListener != null && getCommandContext() != null) {
            getCommandContext().removeCommandListener(commandListener);
            commandListener = null;
        }
        super.dispose();

        if (getDatabaseObject() != null) {
            getCommandContext().resetChanges();
//            // Remove all non-persisted objects
//            for (DBPObject object : getCommandContext().getEditedObjects()) {
//                if (object instanceof DBPPersistedObject && !((DBPPersistedObject)object).isPersisted()) {
//                    dataSource.getContainer().fireEvent(new DBPEvent(DBPEvent.Action.OBJECT_REMOVE, (DBSObject) object));
//                }
//            }
        }
        this.editorMap.clear();
        this.activeEditor = null;
    }

    @Override
    public boolean isDirty()
    {
        final DBECommandContext commandContext = getCommandContext();
        if (commandContext != null && commandContext.isDirty()) {
            return true;
        }

        for (IEditorPart editor : editorMap.values()) {
            if (editor.isDirty()) {
                return true;
            }
        }
        return false;
    }

    public boolean isSaveAsAllowed()
    {
        return this.activeEditor != null && this.activeEditor.isSaveAsAllowed();
    }

    @Override
    public void doSaveAs()
    {
        IEditorPart activeEditor = getActiveEditor();
        if (activeEditor != null && activeEditor.isSaveAsAllowed()) {
            activeEditor.doSaveAs();
        }
    }

    /**
     * Saves data in all nested editors
     * @param monitor progress monitor
     */
    public void doSave(IProgressMonitor monitor)
    {
        if (!isDirty()) {
            return;
        }

        for (IEditorPart editor : editorMap.values()) {
            editor.doSave(monitor);
        }

        final DBECommandContext commandContext = getCommandContext();
        if (commandContext != null && commandContext.isDirty()) {
            saveCommandContext(monitor);
        }

        firePropertyChange(IEditorPart.PROP_DIRTY);
    }

    private void saveCommandContext(IProgressMonitor monitor)
    {
        monitor.beginTask("Preview changes", 1);
        int previewResult = showChanges(true);
        monitor.done();

        final DefaultProgressMonitor monitorWrapper = new DefaultProgressMonitor(monitor);

        if (previewResult == IDialogConstants.PROCEED_ID) {
            Throwable error = null;
            try {
                getCommandContext().saveChanges(monitorWrapper);
            } catch (DBException e) {
                error = e;
            }
            if (getDatabaseObject() instanceof DBSObjectStateful) {
                try {
                    ((DBSObjectStateful) getDatabaseObject()).refreshObjectState(monitorWrapper);
                } catch (DBCException e) {
                    // Just report an error
                    log.error(e);
                }
            }

            if (error == null) {
                // Refresh underlying node
                // It'll refresh database object and all it's descendants
                // So we'll get actual data from database
                final DBNDatabaseNode treeNode = getEditorInput().getTreeNode();
                try {
                    DBeaverCore.getInstance().runInProgressService(new DBRRunnableWithProgress() {
                        public void run(DBRProgressMonitor monitor) throws InvocationTargetException, InterruptedException
                        {
                            try {
                                treeNode.refreshNode(monitor);
                            } catch (DBException e) {
                                throw new InvocationTargetException(e);
                            }
                        }
                    });
                } catch (InvocationTargetException e) {
                    error = e.getTargetException();
                } catch (InterruptedException e) {
                    // ok
                }
            }
            if (error != null) {
                UIUtils.showErrorDialog(getSite().getShell(), "Could not save '" + getDatabaseObject().getName() + "'", null, error);
            }
        }
    }

    public void revertChanges()
    {
        if (isDirty()) {
            if (ConfirmationDialog.showConfirmDialog(
                null,
                PrefConstants.CONFIRM_ENTITY_REVERT,
                ConfirmationDialog.QUESTION,
                getDatabaseObject().getName()) != IDialogConstants.YES_ID)
            {
                return;
            }
            getCommandContext().resetChanges();
            firePropertyChange(IEditorPart.PROP_DIRTY);
        }
    }

    public void undoChanges()
    {
        if (getCommandContext() != null && getCommandContext().getUndoCommand() != null) {
            if (!getDatabaseObject().isPersisted() && getCommandContext().getUndoCommands().size() == 1) {
                //getSite().getPage().closeEditor(this, true);
                //return;
                // Undo of last command in command context will close editor
                // Let's ask user about it
                if (ConfirmationDialog.showConfirmDialog(
                    null,
                    PrefConstants.CONFIRM_ENTITY_REJECT,
                    ConfirmationDialog.QUESTION,
                    ConfirmationDialog.WARNING,
                    getDatabaseObject().getName()) != IDialogConstants.YES_ID)
                {
                    return;
                }
            }
            getCommandContext().undoCommand();
            firePropertyChange(IEditorPart.PROP_DIRTY);
        }
    }

    public void redoChanges()
    {
        if (getCommandContext() != null && getCommandContext().getRedoCommand() != null) {
            getCommandContext().redoCommand();
            firePropertyChange(IEditorPart.PROP_DIRTY);
        }
    }

    public int showChanges(boolean allowSave)
    {
        if (getCommandContext() == null) {
            return IDialogConstants.CANCEL_ID;
        }
        Collection<? extends DBECommand> commands = getCommandContext().getFinalCommands();
        StringBuilder script = new StringBuilder();
        for (DBECommand command : commands) {
            try {
                command.validateCommand();
            } catch (final DBException e) {
                Display.getDefault().syncExec(new Runnable() {
                    public void run()
                    {
                        UIUtils.showErrorDialog(getSite().getShell(), "Validation", e.getMessage());
                    }
                });
                return IDialogConstants.CANCEL_ID;
            }
            IDatabasePersistAction[] persistActions = command.getPersistActions();
            if (!CommonUtils.isEmpty(persistActions)) {
                for (IDatabasePersistAction action : persistActions) {
                    if (script.length() > 0) {
                        script.append('\n');
                    }
                    script.append(action.getScript());
                    script.append(getCommandContext().getDataSourceContainer().getDataSource().getInfo().getScriptDelimiter());
                }
            }
        }

        ChangesPreviewer changesPreviewer = new ChangesPreviewer(script, allowSave);
        getSite().getShell().getDisplay().syncExec(changesPreviewer);
        return changesPreviewer.getResult();
/*

        Shell shell = getSite().getShell();
        ViewTextDialog dialog = new ViewTextDialog(shell, "Script", script.toString());
        dialog.setTextWidth(0);
        dialog.setTextHeight(0);
        dialog.setImage(DBIcon.SQL_PREVIEW.getImage());
        dialog.open();
*/
    }

    protected void createPages()
    {
/*
        {
            IBindingService bindingService = (IBindingService)getSite().getService(IBindingService.class);
            for (Binding binding : bindingService.getBindings()) {
                System.out.println("binding:" + binding);
            }
        }
        {
            ICommandService commandService = (ICommandService)getSite().getService(ICommandService.class);
            for (Command command : commandService.getDefinedCommands()) {
                System.out.println("command:" + command);
            }
        }
*/

        // Command listener
        commandListener = new DBECommandAdapter() {
            @Override
            public void onCommandChange(DBECommand command)
            {
                firePropertyChange(IEditorPart.PROP_DIRTY);
            }
        };
        getCommandContext().addCommandListener(commandListener);

        // Property listener
        addPropertyListener(new IPropertyListener() {
            public void propertyChanged(Object source, int propId)
            {
                if (propId == IEditorPart.PROP_DIRTY) {
                    EntityEditorPropertyTester.firePropertyChange(EntityEditorPropertyTester.PROP_DIRTY);
                    EntityEditorPropertyTester.firePropertyChange(EntityEditorPropertyTester.PROP_CAN_UNDO);
                    EntityEditorPropertyTester.firePropertyChange(EntityEditorPropertyTester.PROP_CAN_REDO);
                }
            }
        });

        super.createPages();

        EditorDefaults editorDefaults;
        synchronized (defaultPageMap) {
            editorDefaults = defaultPageMap.get(getEditorInput().getDatabaseObject().getClass().getName());
        }

        EntityEditorsRegistry editorsRegistry = DBeaverCore.getInstance().getEditorsRegistry();
        DBSObject databaseObject = getEditorInput().getDatabaseObject();

        // Add object editor page
        EntityEditorDescriptor defaultEditor = editorsRegistry.getMainEntityEditor(databaseObject);
        hasPropertiesEditor = false;
        if (defaultEditor != null) {
            hasPropertiesEditor = addEditorTab(defaultEditor);
        }
        if (hasPropertiesEditor) {
            DBNNode node = getEditorInput().getTreeNode();
            setPageText(0, "Properties");
            setPageToolTip(0, node.getNodeType() + " Properties");
            setPageImage(0, node.getNodeIconDefault());
        }
/*
        if (!mainAdded) {
            try {
                DBNNode node = getEditorInput().getTreeNode();
                int index = addPage(new ObjectPropertiesEditor(node), getEditorInput());
                setPageText(index, "Properties");
                if (node instanceof DBNDatabaseNode) {
                    setPageToolTip(index, ((DBNDatabaseNode)node).getMeta().getChildrenType() + " Properties");
                }
                setPageImage(index, node.getNodeIconDefault());
            } catch (PartInitException e) {
                log.error("Error creating object editor");
            }
        }
*/

        // Add contributed pages
        addContributions(EntityEditorDescriptor.POSITION_PROPS);
        addContributions(EntityEditorDescriptor.POSITION_START);
        addContributions(EntityEditorDescriptor.POSITION_MIDDLE);

        // Add navigator tabs
        //addNavigatorTabs();

        // Add contributed pages
        addContributions(EntityEditorDescriptor.POSITION_END);

        String defPageId = getEditorInput().getDefaultPageId();
        if (defPageId == null && editorDefaults != null) {
            defPageId = editorDefaults.pageId;
        }
        if (defPageId != null) {
            IEditorPart defEditorPage = editorMap.get(defPageId);
            if (defEditorPage != null) {
                setActiveEditor(defEditorPage);
            }
        }
        this.activeEditor = getActiveEditor();
        if (activeEditor instanceof IFolderedPart) {
            String defFolderId = getEditorInput().getDefaultFolderId();
            if (defFolderId == null && editorDefaults != null) {
                defFolderId = editorDefaults.folderId;
            }
            if (defFolderId != null) {
                ((IFolderedPart)activeEditor).switchFolder(defFolderId);
            }
        }

        UIUtils.setHelp(getContainer(), IHelpContextIds.CTX_ENTITY_EDITOR);
    }

    private void addNavigatorTabs()
    {
        // Collect tabs from navigator tree model
        final List<TabInfo> tabs = new ArrayList<TabInfo>();
        DBRRunnableWithProgress tabsCollector = new DBRRunnableWithProgress() {
            public void run(DBRProgressMonitor monitor)
            {
                tabs.addAll(collectTabs(monitor));
            }
        };
        DBNDatabaseNode node = getEditorInput().getTreeNode();
        try {
            if (node.isLazyNode()) {
                DBeaverCore.getInstance().runInProgressService(tabsCollector);
            } else {
                tabsCollector.run(VoidProgressMonitor.INSTANCE);
            }
        } catch (InvocationTargetException e) {
            log.error(e.getTargetException());
        } catch (InterruptedException e) {
            // just go further
        }

        for (TabInfo tab : tabs) {
            addNodeTab(tab);
        }
    }

    @Override
    protected void pageChange(int newPageIndex) {
        super.pageChange(newPageIndex);

        activeEditor = getEditor(newPageIndex);
        String editorPageId = getEditorPageId(activeEditor);
        if (editorPageId != null) {
            updateEditorDefaults(editorPageId, null);
        }
        // Fire dirty flag refresh to re-enable Save-As command (which is enabled only for certain pages)
        firePropertyChange(IEditorPart.PROP_DIRTY);
    }

    private String getEditorPageId(IEditorPart editorPart)
    {
        synchronized (editorMap) {
            for (Map.Entry<String,IEditorPart> entry : editorMap.entrySet()) {
                if (entry.getValue() == editorPart) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private void updateEditorDefaults(String pageId, String folderId)
    {
        DBSObject object = getEditorInput().getDatabaseObject();
        if (object != null) {
            synchronized (defaultPageMap) {
                EditorDefaults editorDefaults = defaultPageMap.get(object.getClass().getName());
                if (editorDefaults == null) {
                    editorDefaults = new EditorDefaults(pageId, folderId);
                    defaultPageMap.put(object.getClass().getName(), editorDefaults);
                } else {
                    if (pageId != null) {
                        editorDefaults.pageId = pageId;
                    }
                    if (folderId != null) {
                        editorDefaults.folderId = folderId;
                    }
                }
            }
        }
    }

    public int promptToSaveOnClose()
    {
        final int result = ConfirmationDialog.showConfirmDialog(
            getSite().getShell(),
            PrefConstants.CONFIRM_ENTITY_EDIT_CLOSE,
            ConfirmationDialog.QUESTION_WITH_CANCEL,
            getEditorInput().getTreeNode().getNodeName());
        if (result == IDialogConstants.YES_ID) {
//            getWorkbenchPart().getSite().getPage().saveEditor(this, false);
            return ISaveablePart2.YES;
        } else if (result == IDialogConstants.NO_ID) {
            return ISaveablePart2.NO;
        } else {
            return ISaveablePart2.CANCEL;
        }
    }

    public Object getActiveFolder()
    {
        if (getActiveEditor() instanceof IFolderedPart) {
            ((IFolderedPart)getActiveEditor()).getActiveFolder();
        }
        return null;
    }

    public void switchFolder(String folderId)
    {
        for (IEditorPart editor : editorMap.values()) {
            if (editor instanceof IFolderedPart) {
                if (getActiveEditor() != editor) {
                    setActiveEditor(editor);
                }
                ((IFolderedPart)editor).switchFolder(folderId);
            }
        }
//        if (getActiveEditor() instanceof IFolderedPart) {
//            ((IFolderedPart)getActiveEditor()).switchFolder(folderId);
//        }
    }

    public void addFolderListener(IFolderListener listener)
    {
    }

    public void removeFolderListener(IFolderListener listener)
    {
    }

    private static class TabInfo {
        DBNDatabaseNode node;
        DBXTreeNode meta;
        private TabInfo(DBNDatabaseNode node)
        {
            this.node = node;
        }
        private TabInfo(DBNDatabaseNode node, DBXTreeNode meta)
        {
            this.node = node;
            this.meta = meta;
        }
        public String getName()
        {
            return meta == null ? node.getNodeName() : meta.getChildrenType(node.getObject().getDataSource());
        }
    }

    private List<TabInfo> collectTabs(DBRProgressMonitor monitor)
    {
        List<TabInfo> tabs = new ArrayList<TabInfo>();

        // Add all nested folders as tabs
        DBNNode node = getEditorInput().getTreeNode();
        if (node instanceof DBNDataSource && !((DBNDataSource)node).getDataSourceContainer().isConnected()) {
            // Do not add children tabs
        } else if (node != null) {
            try {
                List<? extends DBNNode> children = node.getChildren(monitor);
                if (children != null) {
                    for (DBNNode child : children) {
                        if (child instanceof DBNDatabaseFolder) {
                            monitor.subTask("Add folder '" + child.getNodeName() + "'");
                            tabs.add(new TabInfo((DBNDatabaseFolder)child));
                        }
                    }
                }
            } catch (DBException e) {
                log.error("Error initializing entity editor", e);
            }
            // Add itself as tab (if it has child items)
            if (node instanceof DBNDatabaseNode) {
                DBNDatabaseNode databaseNode = (DBNDatabaseNode)node;
                List<DBXTreeNode> subNodes = databaseNode.getMeta().getChildren(databaseNode);
                if (subNodes != null) {
                    for (DBXTreeNode child : subNodes) {
                        if (child instanceof DBXTreeItem) {
                            try {
                                if (!((DBXTreeItem)child).isOptional() || databaseNode.hasChildren(monitor, child)) {
                                    monitor.subTask("Add node '" + node.getNodeName() + "'");
                                    tabs.add(new TabInfo((DBNDatabaseNode)node, child));
                                }
                            } catch (DBException e) {
                                log.debug("Can't add child items tab", e);
                            }
                        }
                    }
                }
            }
        }
        return tabs;
    }

    private void addContributions(String position)
    {
        EntityEditorsRegistry editorsRegistry = DBeaverCore.getInstance().getEditorsRegistry();
        final DBSObject databaseObject = getEditorInput().getDatabaseObject();
        DBPObject object;
        if (databaseObject instanceof DBSDataSourceContainer && databaseObject.getDataSource() != null) {
            object = databaseObject.getDataSource();
        } else {
            object = databaseObject;
        }
        List<EntityEditorDescriptor> descriptors = editorsRegistry.getEntityEditors(
            object,
            position);
        for (EntityEditorDescriptor descriptor : descriptors) {
            addEditorTab(descriptor);
        }
    }

    private boolean addEditorTab(EntityEditorDescriptor descriptor)
    {
        try {
            IEditorPart editor = descriptor.createEditor();
            if (editor == null) {
                return false;
            }
            int index = addPage(editor, getEditorInput());
            setPageText(index, descriptor.getName());
            if (descriptor.getIcon() != null) {
                setPageImage(index, descriptor.getIcon());
            }
            if (!CommonUtils.isEmpty(descriptor.getDescription())) {
                setPageToolTip(index, descriptor.getDescription());
            }
            editorMap.put(descriptor.getId(), editor);

            if (editor instanceof IFolderedPart) {
                ((IFolderedPart) editor).addFolderListener(folderListener);
            }

            return true;
        } catch (Exception ex) {
            log.error("Error adding nested editor", ex);
            return false;
        }
    }

    private void addNodeTab(TabInfo tabInfo)
    {
        try {
            EntityNodeEditor nodeEditor = new EntityNodeEditor(tabInfo.node, tabInfo.meta);
            int index = addPage(nodeEditor, getEditorInput());
            if (tabInfo.meta == null) {
                setPageText(index, tabInfo.node.getNodeName());
                setPageImage(index, tabInfo.node.getNodeIconDefault());
                setPageToolTip(index, getEditorInput().getTreeNode().getNodeType() + " " + tabInfo.node.getNodeName());
            } else {
                setPageText(index, tabInfo.meta.getChildrenType(getDataSource()));
                if (tabInfo.meta.getDefaultIcon() != null) {
                    setPageImage(index, tabInfo.meta.getDefaultIcon());
                } else {
                    setPageImage(index, DBIcon.TREE_FOLDER.getImage());
                }
                setPageToolTip(index, tabInfo.meta.getChildrenType(getDataSource()));
            }
            editorMap.put("node." + tabInfo.getName(), nodeEditor);
        } catch (PartInitException ex) {
            log.error("Error adding nested editor", ex);
        }
    }

    public void refreshPart(final Object source)
    {
        // TODO: make smart content refresh
        // Lists and commands should be refreshed only if we make real refresh from remote storage
        // Otherwise just update object's properties
/*
        getEditorInput().getCommandContext().resetChanges();
*/
        DBSObject databaseObject = getEditorInput().getDatabaseObject();
        if (databaseObject != null && databaseObject.isPersisted()) {
            // Refresh visual content in parts
            for (IEditorPart editor : editorMap.values()) {
                if (editor instanceof IRefreshablePart) {
                    ((IRefreshablePart)editor).refreshPart(source);
                }
            }
        }

        setPartName(getEditorInput().getName());
        setTitleImage(getEditorInput().getImageDescriptor());

        if (hasPropertiesEditor) {
            // Update main editor image
            setPageImage(0, getEditorInput().getTreeNode().getNodeIconDefault());
        }
    }

    public DBNNode getRootNode() {
        return getEditorInput().getTreeNode();
    }

    public Viewer getNavigatorViewer()
    {
        IWorkbenchPart activePart = getActiveEditor();
        if (activePart instanceof INavigatorModelView) {
            return ((INavigatorModelView)activePart).getNavigatorViewer();
        }
        return null;
    }

    @Override
    public Object getAdapter(Class adapter) {
        if (adapter == IPropertySheetPage.class) {
            //return new PropertyPageTabbed();
        }
        return super.getAdapter(adapter);
    }

    private class ChangesPreviewer implements Runnable {

        private final StringBuilder script;
        private final boolean allowSave;
        private int result;

        public ChangesPreviewer(StringBuilder script, boolean allowSave)
        {
            this.script = script;
            this.allowSave = allowSave;
        }

        public void run()
        {
            ViewSQLDialog dialog = new ViewSQLDialog(
                getEditorSite(),
                getDataSource().getContainer(),
                allowSave ? "Persist Changes" : "Preview Changes", 
                script.toString());
            dialog.setShowSaveButton(allowSave);
            dialog.setImage(DBIcon.SQL_PREVIEW.getImage());
            result = dialog.open();
        }

        public int getResult()
        {
            return result;
        }
    }

}
