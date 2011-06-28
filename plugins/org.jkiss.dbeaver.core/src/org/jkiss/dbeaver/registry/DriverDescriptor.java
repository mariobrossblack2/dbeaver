/*
 * Copyright (c) 2011, Serge Rieder and others. All Rights Reserved.
 */

package org.jkiss.dbeaver.registry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.osgi.framework.internal.core.BundleHost;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.views.properties.IPropertyDescriptor;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.core.DBeaverCore;
import org.jkiss.dbeaver.model.DBPDataSourceProvider;
import org.jkiss.dbeaver.model.DBPDriver;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DBRRunnableWithProgress;
import org.jkiss.dbeaver.ui.DBIcon;
import org.jkiss.dbeaver.ui.OverlayImageDescriptor;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.AcceptLicenseDialog;
import org.jkiss.dbeaver.ui.dialogs.ConfirmationDialog;
import org.jkiss.dbeaver.ui.preferences.PrefConstants;
import org.jkiss.dbeaver.ui.properties.PropertyDescriptorEx;
import org.jkiss.dbeaver.utils.ContentUtils;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.xml.SAXListener;
import org.jkiss.utils.xml.SAXReader;
import org.jkiss.utils.xml.XMLBuilder;
import org.jkiss.utils.xml.XMLException;
import org.xml.sax.Attributes;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.NumberFormat;
import java.util.*;

/**
 * DriverDescriptor
 */
public class DriverDescriptor extends AbstractDescriptor implements DBPDriver
{
    static final Log log = LogFactory.getLog(DriverDescriptor.class);

    private DataSourceProviderDescriptor providerDescriptor;
    private String id;
    private String category;
    private String name, origName;
    private String description, origDescription;
    private String driverClassName, origClassName;
    private String driverDefaultPort, origDefaultPort;
    private String sampleURL, origSampleURL;
    private String webURL;
    private Image iconPlain;
    private Image iconNormal;
    private Image iconError;
    private boolean supportsDriverProperties;
    private boolean anonymousAccess;
    private boolean custom;
    private boolean modified;
    private boolean disabled;
    private List<DriverFileDescriptor> files = new ArrayList<DriverFileDescriptor>(), origFiles;
    private List<DriverPathDescriptor> pathList = new ArrayList<DriverPathDescriptor>();
    private List<IPropertyDescriptor> connectionPropertyDescriptors = new ArrayList<IPropertyDescriptor>();

    private Map<Object, Object> defaultParameters = new HashMap<Object, Object>();
    private Map<Object, Object> customParameters = new HashMap<Object, Object>();

    private Map<Object, Object> defaultConnectionProperties = new HashMap<Object, Object>();
    private Map<Object, Object> customConnectionProperties = new HashMap<Object, Object>();

    private Class driverClass;
    private boolean isLoaded;
    private Object driverInstance;
    private DriverClassLoader classLoader;

    private transient List<DataSourceDescriptor> usedBy = new ArrayList<DataSourceDescriptor>();

    private transient boolean isFailed = false;

    DriverDescriptor(DataSourceProviderDescriptor providerDescriptor, String id)
    {
        super(providerDescriptor.getContributor());
        this.providerDescriptor = providerDescriptor;
        this.id = id;
        this.custom = true;
        this.iconPlain = new Image(null, providerDescriptor.getIcon(), SWT.IMAGE_COPY);
        makeIconExtensions();
    }

    DriverDescriptor(DataSourceProviderDescriptor providerDescriptor, IConfigurationElement config)
    {
        super(providerDescriptor.getContributor());
        this.providerDescriptor = providerDescriptor;
        this.id = CommonUtils.getString(config.getAttribute(DataSourceConstants.ATTR_ID));
        this.category = CommonUtils.getString(config.getAttribute(DataSourceConstants.ATTR_CATEGORY));
        this.origName = this.name = CommonUtils.getString(config.getAttribute("label"));
        this.origDescription = this.description = config.getAttribute(DataSourceConstants.ATTR_DESCRIPTION);
        this.origClassName = this.driverClassName = config.getAttribute(DataSourceConstants.ATTR_CLASS);
        if (!CommonUtils.isEmpty(config.getAttribute("defaultPort"))) {
            try {
                this.origDefaultPort = this.driverDefaultPort = config.getAttribute("defaultPort");
            }
            catch (NumberFormatException ex) {
                log.warn("Bad default port for driver '" + name + "' specified: " + ex.getMessage());
            }
        }
        this.origSampleURL = this.sampleURL = config.getAttribute("sampleURL");
        this.webURL = config.getAttribute("webURL");
        this.supportsDriverProperties = !"false".equals(config.getAttribute("supportsDriverProperties"));
        this.anonymousAccess = "true".equals(config.getAttribute("anonymous"));
        this.custom = false;
        this.isLoaded = false;

        for (IConfigurationElement lib : config.getChildren(DataSourceConstants.TAG_FILE)) {
            this.files.add(new DriverFileDescriptor(this, lib));
        }
        this.origFiles = new ArrayList<DriverFileDescriptor>(this.files);

        String iconName = config.getAttribute("icon");
        if (!CommonUtils.isEmpty(iconName)) {
            this.iconPlain = iconToImage(iconName);
        }
        if (this.iconPlain == null) {
            this.iconPlain = new Image(null, providerDescriptor.getIcon(), SWT.IMAGE_COPY);
        }
        makeIconExtensions();

        {
            // Connection property groups
            IConfigurationElement[] propElements = config.getChildren(PropertyDescriptorEx.TAG_PROPERTY_GROUP);
            for (IConfigurationElement prop : propElements) {
                connectionPropertyDescriptors.addAll(PropertyDescriptorEx.extractProperties(prop));
            }
        }

        // Connection default properties
        //connectionProperties

        {
            // Driver parameters
            IConfigurationElement[] paramElements = config.getChildren(DataSourceConstants.TAG_PARAMETER);
            for (IConfigurationElement param : paramElements) {
                String paramName = param.getAttribute(DataSourceConstants.ATTR_NAME);
                String paramValue = param.getAttribute(DataSourceConstants.ATTR_VALUE);
                if (CommonUtils.isEmpty(paramValue)) {
                    paramValue = param.getValue();
                }
                if (!CommonUtils.isEmpty(paramName) && !CommonUtils.isEmpty(paramValue)) {
                    defaultParameters.put(paramName, paramValue);
                    customParameters.put(paramName, paramValue);
                }
            }
        }

        {
            // Connection properties
            IConfigurationElement[] propElements = config.getChildren(DataSourceConstants.TAG_PROPERTY);
            for (IConfigurationElement param : propElements) {
                String paramName = param.getAttribute(DataSourceConstants.ATTR_NAME);
                String paramValue = param.getAttribute(DataSourceConstants.ATTR_VALUE);
                if (CommonUtils.isEmpty(paramValue)) {
                    paramValue = param.getValue();
                }
                if (!CommonUtils.isEmpty(paramName) && !CommonUtils.isEmpty(paramValue)) {
                    defaultConnectionProperties.put(paramName, paramValue);
                    customConnectionProperties.put(paramName, paramValue);
                }
            }
        }

        // Create class loader
        this.classLoader = new DriverClassLoader(
            this,
            new URL[0],
            //getClass().getClassLoader());
            ((BundleHost)providerDescriptor.getContributorBundle()).getClassLoader());
    }

    private void makeIconExtensions()
    {
        if (isCustom()) {
            OverlayImageDescriptor customDescriptor = new OverlayImageDescriptor(this.iconPlain.getImageData());
            customDescriptor.setBottomLeft(new ImageDescriptor[]{DBIcon.OVER_CONDITION.getImageDescriptor()});
            this.iconNormal = new Image(this.iconPlain.getDevice(), customDescriptor.getImageData());
        } else {
            this.iconNormal = new Image(this.iconPlain.getDevice(), iconPlain, SWT.IMAGE_COPY);
        }
        OverlayImageDescriptor failedDescriptor = new OverlayImageDescriptor(this.iconNormal.getImageData());
        failedDescriptor.setBottomRight(new ImageDescriptor[] {DBIcon.OVER_ERROR.getImageDescriptor()} );
        this.iconError = new Image(this.iconNormal.getDevice(), failedDescriptor.getImageData());
    }

    public void dispose()
    {
        if (iconPlain != null) {
            iconPlain.dispose();
            iconPlain = null;
        }
        if (iconNormal != null) {
            iconNormal.dispose();
            iconNormal = null;
        }
        if (iconError != null) {
            iconError.dispose();
            iconError = null;
        }
        if (!usedBy.isEmpty()) {
            log.error("Driver '" + getName() + "' still used by " + usedBy.size() + " data sources");
        }
    }

    void addUser(DataSourceDescriptor dataSourceDescriptor)
    {
        usedBy.add(dataSourceDescriptor);
    }

    void removeUser(DataSourceDescriptor dataSourceDescriptor)
    {
        usedBy.remove(dataSourceDescriptor);
    }

    public DriverClassLoader getClassLoader()
    {
        return classLoader;
    }

    public List<DataSourceDescriptor> getUsedBy()
    {
        return usedBy;
    }

    public DataSourceProviderDescriptor getProviderDescriptor()
    {
        return providerDescriptor;
    }

    public DBPDataSourceProvider getDataSourceProvider()
        throws DBException
    {
        return providerDescriptor.getInstance();
    }

    public String getId()
    {
        return id;
    }

    @Property(name = "Driver Category", viewable = true, order = 2)
    public String getCategory()
    {
        return category;
    }

    public void setCategory(String category)
    {
        this.category = category;
    }

    @Property(name = "Driver Name", viewable = true, order = 1)
    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    @Property(name = "Description", viewable = true, order = 100)
    public String getDescription()
    {
        return description;
    }

    public void setDescription(String description)
    {
        this.description = description;
    }

    public String getFullName()
    {
        if (CommonUtils.isEmpty(category)) {
            return name;
        } else {
            return category + " " + name;
        }
    }

    /**
     * Plain icon (without any overlays).
     * @return plain icon
     */
    public Image getPlainIcon()
    {
        return iconPlain;
    }

    /**
     * Driver icon, includes overlays for driver conditions (custom, invalid, etc)..
     * @return icon
     */
    public Image getIcon()
    {
        if (!isLoaded && (isFailed || (isManagable() && !isInternalDriver() && !hasValidLibraries()))) {
            return iconError;
        } else {
            return iconNormal;
        }
    }

    private boolean hasValidLibraries()
    {
        for (DriverFileDescriptor lib : files) {
            if (lib.getFile().exists() || (!lib.isDisabled() && !CommonUtils.isEmpty(lib.getExternalURL()))) {
                return true;
            }
        }
        return false;
    }

    public boolean isCustom()
    {
        return custom;
    }

    public boolean isModified()
    {
        return modified;
    }

    public void setModified(boolean modified)
    {
        this.modified = modified;
    }

    public boolean isDisabled()
    {
        return disabled;
    }

    void setDisabled(boolean disabled)
    {
        this.disabled = disabled;
    }

    @Property(name = "Driver Class", viewable = true, order = 2)
    public String getDriverClassName()
    {
        return driverClassName;
    }

    public void setDriverClassName(String driverClassName)
    {
        if (this.driverClassName == null || !this.driverClassName.equals(driverClassName)) {
            this.driverClassName = driverClassName;
            this.driverInstance = null;
            this.driverClass = null;
            this.isLoaded = false;
        }
    }

    public Object getDriverInstance()
        throws DBException
    {
        if (driverInstance == null) {
            loadDriver();
        }
        return driverInstance;
    }

    Object createDriverInstance()
        throws DBException
    {
        try {
            return driverClass.newInstance();
        }
        catch (InstantiationException ex) {
            throw new DBException("Can't instantiate driver class", ex);
        }
        catch (IllegalAccessException ex) {
            throw new DBException("Illegal access", ex);
        }
        catch (ClassCastException ex) {
            throw new DBException("Bad driver class name specified", ex);
        }
        catch (Throwable ex) {
            throw new DBException("Error during driver instantiation", ex);
        }
    }

    public String getDefaultPort()
    {
        return driverDefaultPort;
    }

    public void setDriverDefaultPort(String driverDefaultPort)
    {
        this.driverDefaultPort = driverDefaultPort;
    }

    @Property(name = "URL", viewable = true, order = 3)
    public String getSampleURL()
    {
        return sampleURL;
    }

    public void setSampleURL(String sampleURL)
    {
        this.sampleURL = sampleURL;
    }

    public String getWebURL()
    {
        return webURL;
    }

    public boolean supportsDriverProperties()
    {
        return this.supportsDriverProperties;
    }

    public boolean isAnonymousAccess()
    {
        return anonymousAccess;
    }

    public void setAnonymousAccess(boolean anonymousAccess)
    {
        this.anonymousAccess = anonymousAccess;
    }

    public boolean isManagable()
    {
        return getProviderDescriptor().isDriversManagable();
    }

    public boolean isInternalDriver()
    {
        return driverClassName != null && driverClassName.indexOf("sun.jdbc") != -1;
    }

/*
    public boolean isLoaded()
    {
        return isLoaded;
    }

    public Class getDriverClass()
    {
        return driverClass;
    }

    public DriverClassLoader getClassLoader()
    {
        return classLoader;
    }
*/

    public List<DriverFileDescriptor> getFiles()
    {
        return files;
    }

    public DriverFileDescriptor getLibrary(String path)
    {
        for (DriverFileDescriptor lib : files) {
            if (lib.getPath().equals(path)) {
                return lib;
            }
        }
        return null;
    }

    public DriverFileDescriptor addLibrary(String path)
    {
        for (DriverFileDescriptor lib : files) {
            if (lib.getPath().equals(path)) {
                return lib;
            }
        }
        DriverFileDescriptor lib = new DriverFileDescriptor(this, path);
        this.files.add(lib);
        return lib;
    }

    public boolean addLibrary(DriverFileDescriptor descriptor)
    {
        if (!files.contains(descriptor)) {
            this.files.add(descriptor);
            return true;
        }
        return false;
    }

    public boolean removeLibrary(DriverFileDescriptor lib)
    {
        if (!lib.isCustom()) {
            lib.setDisabled(true);
            return true;
        } else {
            return this.files.remove(lib);
        }
    }

    public Collection<String> getOrderedPathList()
    {
        if (pathList.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>(pathList.size());
        for (DriverPathDescriptor path : pathList) {
            if (path.isEnabled()) {
                result.add(path.getPath());
            }
        }
        return result;
    }

    public List<DriverPathDescriptor> getPathList()
    {
        return pathList;
    }

    void addPath(DriverPathDescriptor path)
    {
        pathList.add(path);
    }
    
    public void setPathList(List<DriverPathDescriptor> pathList)
    {
        this.pathList = pathList;
    }

    public List<IPropertyDescriptor> getConnectionPropertyDescriptors()
    {
        return connectionPropertyDescriptors;
    }

    public Map<Object, Object> getDefaultConnectionProperties()
    {
        return defaultConnectionProperties;
    }

    public Map<Object, Object> getConnectionProperties()
    {
        return customConnectionProperties;
    }

    public void setConnectionProperty(String name, String value)
    {
        customConnectionProperties.put(name, value);
    }

    public void setConnectionProperties(Map<Object, Object> parameters)
    {
        customConnectionProperties.clear();
        customConnectionProperties.putAll(parameters);
    }

    public Map<Object, Object> getDefaultDriverParameters()
    {
        return defaultParameters;
    }

    public Map<Object, Object> getDriverParameters()
    {
        return customParameters;
    }

    public Object getDriverParameter(String name)
    {
        return customParameters.get(name);
    }

    public void setDriverParameter(String name, String value)
    {
        customParameters.put(name, value);
    }

    public void setDriverParameters(Map<Object, Object> parameters)
    {
        customParameters.clear();
        customParameters.putAll(parameters);
    }

    public String getLicense()
    {
        for (DriverFileDescriptor file : files) {
            if (file.getType() == DriverFileType.license) {
                final File licenseFile = file.getFile();
                if (licenseFile.exists()) {
                    try {
                        return ContentUtils.readFileToString(licenseFile);
                    } catch (IOException e) {
                        log.warn(e);
                    }
                }
            }
        }
        return null;
    }

    public void loadDriver()
        throws DBException
    {
        this.loadDriver(false);
    }

    public void loadDriver(boolean forceReload)
        throws DBException
    {
        if (isLoaded && !forceReload) {
            return;
        }
        isLoaded = false;

        loadLibraries();

        try {
            try {
                if (this.isInternalDriver()) {
                    // Use system class loader
                    driverClass = Class.forName(driverClassName);
                } else {
                    // Load driver classes into core module using plugin class loader
                    driverClass = Class.forName(driverClassName, true, classLoader);
                }
            }
            catch (Throwable ex) {
                throw new DBException("Can't load driver class '" + driverClassName + "'", ex);
            }

            // Create driver instance
            if (!this.isInternalDriver()) {
                driverInstance = createDriverInstance();
            }

            isLoaded = true;
            isFailed = false;
        } catch (DBException e) {
            isFailed = true;
            throw e;
        }
    }

    private void loadLibraries()
    {
        this.classLoader = null;

        validateFilesPresence();

        List<URL> libraryURLs = new ArrayList<URL>();
        // Load libraries
        for (DriverFileDescriptor file : files) {
            if (file.isDisabled() || file.getType() != DriverFileType.jar) {
                continue;
            }
            URL url;
            try {
                url = file.getFile().toURI().toURL();
            } catch (MalformedURLException e) {
                log.error(e);
                continue;
            }
            libraryURLs.add(url);
        }
        // Make class loader
        this.classLoader = new DriverClassLoader(
            this,
            libraryURLs.toArray(new URL[libraryURLs.size()]),
            ((BundleHost)providerDescriptor.getContributorBundle()).getClassLoader());
    }

    public void validateFilesPresence()
    {
        final List<DriverFileDescriptor> downloadCandidates = new ArrayList<DriverFileDescriptor>();
        for (DriverFileDescriptor file : files) {
            if (file.isDisabled() || file.getExternalURL() == null || !file.isLocal()) {
                // Nothing we can do about it
                continue;
            }
            if (!file.matchesCurrentPlatform()) {
                // Wrong OS or architecture
                continue;
            }
            final File libraryFile = file.getLocalFile();
            if (!libraryFile.exists()) {
                downloadCandidates.add(file);
            }
        }

        if (!downloadCandidates.isEmpty()) {
            final StringBuilder libNames = new StringBuilder();
            for (DriverFileDescriptor lib : downloadCandidates) {
                if (libNames.length() > 0) libNames.append(", ");
                libNames.append(lib.getPath());
            }
            Display.getDefault().syncExec(new Runnable() {
                public void run()
                {
                    if (ConfirmationDialog.showConfirmDialog(
                        null,
                        PrefConstants.CONFIRM_DRIVER_DOWNLOAD,
                        ConfirmationDialog.QUESTION,
                        ConfirmationDialog.WARNING,
                        getName(),
                        libNames) == IDialogConstants.YES_ID)
                    {
                        // Download drivers
                        downloadLibraryFiles(downloadCandidates);
                    }
                }
            });
        }
    }

    private void downloadLibraryFiles(final List<DriverFileDescriptor> files)
    {
        if (!acceptDriverLicenses()) {
            return;
        }

        DBeaverCore.getInstance().runInProgressDialog(new DBRRunnableWithProgress() {
            public void run(DBRProgressMonitor monitor) throws InvocationTargetException, InterruptedException
            {
                for (DriverFileDescriptor lib : files) {
                    try {
                        final boolean success = downloadLibraryFile(monitor, lib);
                        if (!success) {
                            break;
                        }
                    } catch (final Exception e) {
                        UIUtils.showErrorDialog(
                            null,
                            "Download driver",
                            "Can't download library '" + getName() + "' file",
                            e);
                    }
                }
            }
        });
    }

    private boolean acceptDriverLicenses()
    {
        // User must accept all licenses before actual drivers download
        for (final DriverFileDescriptor file : getFiles()) {
            if (file.getType() == DriverFileType.license) {
                final File libraryFile = file.getLocalFile();
                if (!libraryFile.exists()) {
                    try {
                        DBeaverCore.getInstance().runInProgressService(new DBRRunnableWithProgress() {
                            public void run(DBRProgressMonitor monitor) throws InvocationTargetException, InterruptedException
                            {
                                try {
                                    downloadLibraryFile(monitor, file);
                                } catch (final Exception e) {
                                    log.warn("Can't obtain driver license", e);
                                }
                            }
                        });
                    } catch (Exception e) {
                        log.warn(e);
                    }
                }
                if (libraryFile.exists()) {
                    try {
                        String licenseText = ContentUtils.readFileToString(libraryFile);
                        if (!AcceptLicenseDialog.acceptLicense(
                            DBeaverCore.getActiveWorkbenchShell(),
                            "You have to accept license of '" + this.getFullName() + " ' to continue",
                            licenseText)) {
                            return false;
                        }

                    } catch (IOException e) {
                        log.warn("Can't read license text", e);
                    }
                }
            }
        }
        return true;
    }

    private boolean downloadLibraryFile(DBRProgressMonitor monitor, DriverFileDescriptor file) throws IOException
    {
        URL url = new URL(file.getExternalURL());
        final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setReadTimeout(10000);
        connection.setConnectTimeout(10000);
        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(true);
        connection.connect();
        if (connection.getResponseCode() != 200) {
            throw new IOException("Can't find driver file '" + url + "': " + connection.getResponseMessage());
        }
        final int contentLength = connection.getContentLength();
        //final String contentType = connection.getContentType();
        monitor.beginTask("Download " + file.getExternalURL(), contentLength);
        boolean success = false;
        final File localFile = file.getLocalFile();
        final File localDir = localFile.getParentFile();
        if (!localDir.exists()) {
            if (!localDir.mkdirs()) {
                log.warn("Can't create directory for local driver file '" + localDir.getAbsolutePath() + "'");
            }
        }
        final OutputStream outputStream = new FileOutputStream(localFile);
        try {
            final InputStream inputStream = connection.getInputStream();
            try {
                final NumberFormat numberFormat = NumberFormat.getNumberInstance();
                byte[] buffer = new byte[10000];
                int totalRead = 0;
                for (;;) {
                    if (monitor.isCanceled()) {
                        break;
                    }
                    monitor.subTask(numberFormat.format(totalRead) + "/" + numberFormat.format(contentLength));
                    final int count = inputStream.read(buffer);
                    if (count <= 0) {
                        success = true;
                        break;
                    }
                    outputStream.write(buffer, 0, count);
                    monitor.worked(count);
                    totalRead += count;
                }
            }
            finally {
                ContentUtils.close(inputStream);
            }
        } finally {
            ContentUtils.close(outputStream);
            if (!success) {
                if (!localFile.delete()) {
                    log.warn("Can't delete local driver file '" + localFile.getAbsolutePath() + "'");
                }
            }
        }
        monitor.done();
        return success;
    }

    public String getOrigName()
    {
        return origName;
    }

    public String getOrigDescription()
    {
        return origDescription;
    }

    public String getOrigClassName()
    {
        return origClassName;
    }

    public String getOrigDefaultPort()
    {
        return origDefaultPort;
    }

    public String getOrigSampleURL()
    {
        return origSampleURL;
    }

    public List<DriverFileDescriptor> getOrigFiles()
    {
        return origFiles;
    }

    public static File getDriversContribFolder() throws IOException
    {
        return new File(Platform.getInstallLocation().getDataArea("drivers").toExternalForm());
    }

    public void serialize(XMLBuilder xml, boolean export)
        throws IOException
    {
        xml.startElement(DataSourceConstants.TAG_DRIVER);
        if (export) {
            xml.addAttribute(DataSourceConstants.ATTR_PROVIDER, providerDescriptor.getId());
        }
        xml.addAttribute(DataSourceConstants.ATTR_ID, this.getId());
        if (this.isDisabled()) {
            xml.addAttribute(DataSourceConstants.ATTR_DISABLED, true);
        }
        if (!CommonUtils.isEmpty(this.getCategory())) {
            xml.addAttribute(DataSourceConstants.ATTR_CATEGORY, this.getCategory());
        }
        xml.addAttribute(DataSourceConstants.ATTR_CUSTOM, this.isCustom());
        xml.addAttribute(DataSourceConstants.ATTR_NAME, this.getName());
        xml.addAttribute(DataSourceConstants.ATTR_CLASS, this.getDriverClassName());
        xml.addAttribute(DataSourceConstants.ATTR_URL, this.getSampleURL());
        if (this.getDefaultPort() != null) {
            xml.addAttribute(DataSourceConstants.ATTR_PORT, this.getDefaultPort());
        }
        xml.addAttribute(DataSourceConstants.ATTR_DESCRIPTION, CommonUtils.getString(this.getDescription()));

        // Libraries
        for (DriverFileDescriptor lib : this.getFiles()) {
            if ((export && !lib.isDisabled()) || lib.isCustom() || lib.isDisabled()) {
                xml.startElement(DataSourceConstants.TAG_LIBRARY);
                xml.addAttribute(DataSourceConstants.ATTR_PATH, lib.getPath());
                if (lib.getType() == DriverFileType.jar && lib.isDisabled()) {
                    xml.addAttribute(DataSourceConstants.ATTR_DISABLED, true);
                }
                xml.endElement();
            }
        }

        // Path list
        for (DriverPathDescriptor path : this.getPathList()) {
            xml.startElement(DataSourceConstants.TAG_PATH);
            xml.addAttribute(DataSourceConstants.ATTR_PATH, path.getPath());
            if (!CommonUtils.isEmpty(path.getComment())) {
                xml.addAttribute(DataSourceConstants.ATTR_COMMENT, path.getComment());
            }
            xml.addAttribute(DataSourceConstants.ATTR_ENABLED, path.isEnabled());
            xml.endElement();
        }
        
        // Parameters
        for (Map.Entry<Object, Object> paramEntry : customParameters.entrySet()) {
            if (!CommonUtils.equalObjects(paramEntry.getValue(), defaultParameters.get(paramEntry.getKey()))) {
                xml.startElement(DataSourceConstants.TAG_PARAMETER);
                xml.addAttribute(DataSourceConstants.ATTR_NAME, CommonUtils.toString(paramEntry.getKey()));
                xml.addAttribute(DataSourceConstants.ATTR_VALUE, CommonUtils.toString(paramEntry.getValue()));
                xml.endElement();
            }
        }

        // Properties
        for (Map.Entry<Object, Object> propEntry : customConnectionProperties.entrySet()) {
            if (!CommonUtils.equalObjects(propEntry.getValue(), defaultConnectionProperties.get(propEntry.getKey()))) {
                xml.startElement(DataSourceConstants.TAG_PROPERTY);
                xml.addAttribute(DataSourceConstants.ATTR_NAME, CommonUtils.toString(propEntry.getKey()));
                xml.addAttribute(DataSourceConstants.ATTR_VALUE, CommonUtils.toString(propEntry.getValue()));
                xml.endElement();
            }
        }

        xml.endElement();
    }

    static class DriversParser implements SAXListener
    {
        DataSourceProviderDescriptor curProvider;
        DriverDescriptor curDriver;

        public void saxStartElement(SAXReader reader, String namespaceURI, String localName, Attributes atts)
            throws XMLException
        {
            if (localName.equals(DataSourceConstants.TAG_PROVIDER)) {
                curProvider = null;
                curDriver = null;
                String idAttr = atts.getValue(DataSourceConstants.ATTR_ID);
                if (CommonUtils.isEmpty(idAttr)) {
                    log.warn("No id for driver provider");
                    return;
                }
                curProvider = DBeaverCore.getInstance().getDataSourceProviderRegistry().getDataSourceProvider(idAttr);
                if (curProvider == null) {
                    log.warn("Datasource provider '" + idAttr + "' not found");
                }
            } else if (localName.equals(DataSourceConstants.TAG_DRIVER)) {
                curDriver = null;
                if (curProvider == null) {
                    String providerId = atts.getValue(DataSourceConstants.ATTR_PROVIDER);
                    if (!CommonUtils.isEmpty(providerId)) {
                        curProvider = DBeaverCore.getInstance().getDataSourceProviderRegistry().getDataSourceProvider(providerId);
                        if (curProvider == null) {
                            log.warn("Datasource provider '" + providerId + "' not found");
                        }
                    }
                    if (curProvider == null) {
                        log.warn("Driver outside of datasource provider");
                        return;
                    }
                }
                String idAttr = atts.getValue(DataSourceConstants.ATTR_ID);
                curDriver = curProvider.getDriver(idAttr);
                if (curDriver == null) {
                    curDriver = new DriverDescriptor(curProvider, idAttr);
                    curProvider.addDriver(curDriver);
                }
                curDriver.setCategory(atts.getValue(DataSourceConstants.ATTR_CATEGORY));
                curDriver.setName(atts.getValue(DataSourceConstants.ATTR_NAME));
                curDriver.setDescription(atts.getValue(DataSourceConstants.ATTR_DESCRIPTION));
                curDriver.setDriverClassName(atts.getValue(DataSourceConstants.ATTR_CLASS));
                curDriver.setSampleURL(atts.getValue(DataSourceConstants.ATTR_URL));
                curDriver.setDriverDefaultPort(atts.getValue(DataSourceConstants.ATTR_PORT));
                curDriver.setModified(true);
                String disabledAttr = atts.getValue(DataSourceConstants.ATTR_DISABLED);
                if ("true".equals(disabledAttr)) {
                    curDriver.setDisabled(true);
                }
            } else if (localName.equals(DataSourceConstants.TAG_FILE) || localName.equals(DataSourceConstants.TAG_LIBRARY)) {
                if (curDriver == null) {
                    log.warn("File outside of driver");
                    return;
                }
                String path = atts.getValue(DataSourceConstants.ATTR_PATH);
                DriverFileDescriptor lib = curDriver.getLibrary(path);
                String disabledAttr = atts.getValue(DataSourceConstants.ATTR_DISABLED);
                if (lib != null && "true".equals(disabledAttr)) {
                    lib.setDisabled(true);
                } else if (lib == null) {
                    curDriver.addLibrary(path);
                }
            } else if (localName.equals(DataSourceConstants.TAG_PATH)) {
                DriverPathDescriptor path = new DriverPathDescriptor();
                path.setPath(atts.getValue(DataSourceConstants.ATTR_PATH));
                path.setComment(atts.getValue(DataSourceConstants.ATTR_COMMENT));
                path.setEnabled("true".equals(atts.getValue(DataSourceConstants.ATTR_ENABLED)));
                curDriver.addPath(path);
            } else if (localName.equals(DataSourceConstants.TAG_PARAMETER)) {
                if (curDriver == null) {
                    log.warn("Parameter outside of driver");
                    return;
                }
                final String paramName = atts.getValue(DataSourceConstants.ATTR_NAME);
                final String paramValue = atts.getValue(DataSourceConstants.ATTR_VALUE);
                if (!CommonUtils.isEmpty(paramName) && !CommonUtils.isEmpty(paramValue)) {
                    curDriver.setDriverParameter(paramName, paramValue);
                }
            } else if (localName.equals(DataSourceConstants.TAG_PROPERTY)) {
                if (curDriver == null) {
                    log.warn("Property outside of driver");
                    return;
                }
                final String paramName = atts.getValue(DataSourceConstants.ATTR_NAME);
                final String paramValue = atts.getValue(DataSourceConstants.ATTR_VALUE);
                if (!CommonUtils.isEmpty(paramName) && !CommonUtils.isEmpty(paramValue)) {
                    curDriver.setConnectionProperty(paramName, paramValue);
                }
            }
        }

        public void saxText(SAXReader reader, String data) {}

        public void saxEndElement(SAXReader reader, String namespaceURI, String localName) {}
    }

    public static class MetaURL {

        private List<String> urlComponents = new ArrayList<String>();
        private Set<String> availableProperties = new HashSet<String>();
        private Set<String> requiredProperties = new HashSet<String>();

        public List<String> getUrlComponents()
        {
            return urlComponents;
        }

        public Set<String> getAvailableProperties()
        {
            return availableProperties;
        }

        public Set<String> getRequiredProperties()
        {
            return requiredProperties;
        }
    }

    public static MetaURL parseSampleURL(String sampleURL) throws DBException
    {
        MetaURL metaURL = new MetaURL();
        int offsetPos = 0;
        for (; ;) {
            int divPos = sampleURL.indexOf('{', offsetPos);
            if (divPos == -1) {
                break;
            }
            int divPos2 = sampleURL.indexOf('}', divPos);
            if (divPos2 == -1) {
                throw new DBException("Bad sample URL: " + sampleURL);
            }
            String propName = sampleURL.substring(divPos + 1, divPos2);
            boolean isOptional = false;
            int optDiv1 = sampleURL.lastIndexOf('[', divPos);
            int optDiv1c = sampleURL.lastIndexOf(']', divPos);
            int optDiv2 = sampleURL.indexOf(']', divPos2);
            int optDiv2c = sampleURL.indexOf('[', divPos2);
            if (optDiv1 != -1 && optDiv2 != -1 && (optDiv1c == -1 || optDiv1c < optDiv1) && (optDiv2c == -1 || optDiv2c > optDiv2)) {
                divPos = optDiv1;
                divPos2 = optDiv2;
                isOptional = true;
            }
            if (divPos > offsetPos) {
                metaURL.urlComponents.add(sampleURL.substring(offsetPos, divPos));
            }
            metaURL.urlComponents.add(sampleURL.substring(divPos, divPos2 + 1));
            metaURL.availableProperties.add(propName);
            if (!isOptional) {
                metaURL.requiredProperties.add(propName);
            }
            offsetPos = divPos2 + 1;
        }
        if (offsetPos < sampleURL.length() - 1) {
            metaURL.urlComponents.add(sampleURL.substring(offsetPos));
        }
/*
        // Check for required parts
        for (String component : urlComponents) {
            boolean isRequired = !component.startsWith("[");
            int divPos = component.indexOf('{');
            if (divPos != -1) {
                int divPos2 = component.indexOf('}', divPos);
                if (divPos2 != -1) {
                    String propName = component.substring(divPos + 1, divPos2);
                    availableProperties.add(propName);
                    if (isRequired) {
                        requiredProperties.add(propName);
                    }
                }
            }
        }
*/
        return metaURL;
    }

}

