/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.tomee.embedded;

import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Manager;
import org.apache.catalina.Server;
import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardServer;
import org.apache.catalina.session.ManagerBase;
import org.apache.catalina.session.StandardManager;
import org.apache.catalina.startup.Catalina;
import org.apache.catalina.startup.CatalinaProperties;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.http11.Http11Protocol;
import org.apache.openejb.AppContext;
import org.apache.openejb.BeanContext;
import org.apache.openejb.Injector;
import org.apache.openejb.NoSuchApplicationException;
import org.apache.openejb.OpenEJB;
import org.apache.openejb.OpenEJBException;
import org.apache.openejb.UndeployException;
import org.apache.openejb.assembler.WebAppDeployer;
import org.apache.openejb.assembler.classic.AppInfo;
import org.apache.openejb.assembler.classic.Assembler;
import org.apache.openejb.assembler.classic.EjbJarInfo;
import org.apache.openejb.assembler.classic.EnterpriseBeanInfo;
import org.apache.openejb.assembler.classic.WebAppInfo;
import org.apache.openejb.config.AnnotationDeployer;
import org.apache.openejb.config.AppModule;
import org.apache.openejb.config.ConfigurationFactory;
import org.apache.openejb.config.DeploymentLoader;
import org.apache.openejb.config.DeploymentsResolver;
import org.apache.openejb.config.EjbModule;
import org.apache.openejb.config.FinderFactory;
import org.apache.openejb.config.NewLoaderLogic;
import org.apache.openejb.config.WebModule;
import org.apache.openejb.config.WebappAggregatedArchive;
import org.apache.openejb.jee.Beans;
import org.apache.openejb.jee.EjbJar;
import org.apache.openejb.jee.ManagedBean;
import org.apache.openejb.jee.TransactionType;
import org.apache.openejb.jee.WebApp;
import org.apache.openejb.jee.oejb3.EjbDeployment;
import org.apache.openejb.jee.oejb3.OpenejbJar;
import org.apache.openejb.loader.Files;
import org.apache.openejb.loader.IO;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.loader.provisining.ProvisioningResolver;
import org.apache.openejb.spi.ContainerSystem;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;
import org.apache.openejb.util.classloader.URLClassLoaderFirst;
import org.apache.tomee.catalina.TomEERuntimeException;
import org.apache.tomee.catalina.TomcatLoader;
import org.apache.tomee.catalina.session.QuickSessionManager;
import org.apache.tomee.embedded.internal.StandardContextCustomizer;
import org.apache.tomee.util.QuickServerXmlParser;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.Velocity;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.log.NullLogChute;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.apache.xbean.finder.filter.Filter;
import org.apache.xbean.finder.filter.Filters;
import org.codehaus.swizzle.stream.ReplaceStringsInputStream;

import javax.naming.Context;
import javax.naming.NamingException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * @version $Rev$ $Date$
 */
public class Container implements AutoCloseable {
    static {
        // org.apache.naming
        Assembler.installNaming("org.apache.naming", true);
    }

    private final Map<String, String> moduleIds = new HashMap<>(); // TODO: manage multimap
    private final Map<String, AppContext> appContexts = new HashMap<>(); // TODO: manage multimap
    private final Map<String, AppInfo> infos = new HashMap<>(); // TODO: manage multimap
    protected Configuration configuration;
    private File base;
    private ConfigurationFactory configurationFactory;
    private Assembler assembler;
    private InternalTomcat tomcat;

    // start the container directly
    public Container(final Configuration configuration) {
        setup(configuration);
        try {
            start();
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Container() {
        this.configuration = new Configuration();
        this.configuration.setHttpPort(23880);
        this.configuration.setStopPort(23881);
    }

    public Container deployClasspathAsWebApp() {
        return deployClasspathAsWebApp("", null);
    }

    public Container deployClasspathAsWebApp(final String context, final File docBase, final String... dependencies) {
        final List<URL> jarList = new DeploymentsResolver.ClasspathSearcher().loadUrls(Thread.currentThread().getContextClassLoader()).getUrls();
        if (dependencies != null) {
            for (final String dep : dependencies) {
                final Set<String> strings = SystemInstance.get().getComponent(ProvisioningResolver.class).realLocation(dep);
                for (final String path : strings) {
                    try {
                        jarList.add(new File(path).toURI().toURL());
                    } catch (final MalformedURLException e) {
                        throw new IllegalArgumentException(e);
                    }
                }
            }
        }

        return deployPathsAsWebapp(context, jarList, docBase);
    }

    public Container deployPathsAsWebapp(final String context, final List<URL> jarList, final File docBase) {
        final ClassLoader loader = Thread.currentThread().getContextClassLoader();
        final SystemInstance systemInstance = SystemInstance.get();

        String contextRoot = context == null ? "" : context;
        if (!contextRoot.isEmpty() && !contextRoot.startsWith("/")) {
            contextRoot = "/" + context;
        }

        final File jarLocation = docBase == null ? fakeRootDir() : docBase;
        final WebModule webModule = new WebModule(new WebApp(), contextRoot, loader, jarLocation.getAbsolutePath(), contextRoot);
        if (docBase == null) {
            webModule.getProperties().put("fakeJarLocation", "true");
        }
        webModule.setUrls(jarList);
        webModule.setAddedUrls(Collections.<URL>emptyList());
        webModule.setRarUrls(Collections.<URL>emptyList());
        webModule.setScannableUrls(jarList);
        try {
            webModule.setFinder(
                new FinderFactory.OpenEJBAnnotationFinder(
                    // skip container classes in scanning for shades
                    new WebappAggregatedArchive(webModule, jarList, jarList.size() == 1 ? new ContainerFilter(configuration.getProperties()) /* shade */ : null))
                        .link());
        } catch (final Exception e) {
            throw new IllegalArgumentException(e);
        }

        DeploymentLoader.addBeansXmls(webModule);

        final AppModule app = new AppModule(loader, null);
        app.setStandloneWebModule();
        try {
            webModule.getAltDDs().putAll(DeploymentLoader.getWebDescriptors(jarLocation));
            DeploymentLoader.addWebModule(webModule, app);
            DeploymentLoader.addWebModuleDescriptors(new File(webModule.getJarLocation()).toURI().toURL(), webModule, app);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }

        addCallersAsEjbModule(loader, app);

        systemInstance.addObserver(new StandardContextCustomizer(webModule));

        try {
            final AppInfo appInfo = configurationFactory.configureApplication(app);
            systemInstance.getComponent(Assembler.class).createApplication(appInfo, loader /* don't recreate a classloader */);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }

        return this;
    }

    private static boolean isContainer(final String[] forced, final String[] skipped, final String name) { // TODO: use caching and split name on '.', should be faster
        if (forced != null && startsWith(forced, name)) {
            return false;
        }
        if (skipped != null && startsWith(skipped, name)) {
            return true;
        }

        if (URLClassLoaderFirst.shouldSkip(name)) {
            return true;
        }
        if (name.startsWith("javax.")) {
            return true; // embedded case, no enrichment or whatever
        }
        if (name.startsWith("org.")) {
            final String org = name.substring("org.".length());
            if (org.startsWith("apache.")) {
                final String sub = org.substring("apache.".length());
                if (sub.startsWith("myfaces.")) {
                    return !sub.contains("cdi.");
                }
                if (sub.startsWith("cxf.")
                        || sub.startsWith("oro.")
                        || sub.startsWith("ws.")
                        || sub.startsWith("jcp.")
                        || sub.startsWith("openejb.")
                        || sub.startsWith("tomee.")
                        || sub.startsWith("tomcat.")
                        || sub.startsWith("juli.")
                        || sub.startsWith("johnzon.")
                        || sub.startsWith("activemq.")
                        || sub.startsWith("neethi.")
                        || sub.startsWith("xml.")
                        || sub.startsWith("velocity.")
                        || sub.startsWith("wss4j.")
                        || sub.startsWith("commons.logging.")) {
                    return true;
                }
            }
            if (org.startsWith("metatype.sxc.")
                    || org.startsWith("openejb.")
                    || org.startsWith("slf4j.")
                    || org.startsWith("fusesource.hawtbuf.")
                    || org.startsWith("objectweb.howl.")) {
                return true;
            }
            if (org.startsWith("joda.time.")) {
                return true;
            }
            if (org.startsWith("opensaml.")) {
                return true;
            }
            if (org.startsWith("codehaus.stax2.")) {
                return true;
            }
            if (org.startsWith("jvnet.mimepull.")) {
                return true;
            }
            if (org.startsWith("jasypt.")) {
                return true;
            }
            if (org.startsWith("junit.")) {
                return true;
            }
            if (org.startsWith("hamcrest.")) {
                return true;
            }
            if (org.startsWith("swizzle.")) {
                return true;
            }
        }
        if (name.startsWith("com.")) {
            final String com = name.substring("com.".length());
            if (com.startsWith("ctc.wstx.") || com.startsWith("ibm.wsdl.")) {
                return true;
            }
        }
        return name.startsWith("net.sf.ehcache.")
                || name.startsWith("junit.")
                || name.startsWith("serp.");
    }

    private static boolean startsWith(String[] forced, String name) {
        for (final String prefix : forced) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static void addCallersAsEjbModule(final ClassLoader loader, final AppModule app) {
        final Set<String> callers = NewLoaderLogic.callers(Filters.classes(Container.class.getName(), "org.apache.openejb.maven.plugins.TomEEEmbeddedMojo"));
        if (callers.isEmpty()) {
            return;
        }
        final EjbJar ejbJar = new EjbJar();
        final OpenejbJar openejbJar = new OpenejbJar();

        for (final String caller : callers) {
            try {
                if (!AnnotationDeployer.isInstantiable(loader.loadClass(caller))) {
                    continue;
                }
            } catch (final ClassNotFoundException e) {
                continue;
            }

            final String name = caller.replace("$", "_");
            final ManagedBean bean = ejbJar.addEnterpriseBean(new ManagedBean(caller.replace("$", "_"), caller, true));
            bean.localBean();
            bean.setTransactionType(TransactionType.BEAN);
            final EjbDeployment ejbDeployment = openejbJar.addEjbDeployment(bean);
            ejbDeployment.setDeploymentId(name);
        }
        final EjbModule ejbModule = new EjbModule(ejbJar, openejbJar);
        ejbModule.setBeans(new Beans());
        app.getEjbModules().add(ejbModule);
    }

    private File fakeRootDir() {
        final File root = new File(configuration.getTempDir());
        Files.mkdirs(root);
        Files.deleteOnExit(root);
        return root;
    }

    private static boolean sameApplication(final File file, final WebAppInfo webApp) {
        String filename = file.getName();
        if (filename.endsWith(".war")) {
            filename = filename.substring(0, filename.length() - 4);
        }
        return filename.equals(webApp.moduleId);
    }

    private static String lastPart(final String name, final String defaultValue) {
        final int idx = name.lastIndexOf("/");
        final int space = name.lastIndexOf(" ");
        if (idx >= 0 && space < idx) {
            return name.substring(idx);
        } else if (idx < 0 && space < 0) {
            return name;
        }
        return defaultValue;
    }

    public void setup(final Configuration configuration) {
        this.configuration = configuration;

        if (configuration.isQuickSession()) {
            tomcat = new TomcatWithFastSessionIDs();
        } else {
            tomcat = new InternalTomcat();
        }

        // create basic installation in setup to be able to handle anything the caller does between setup() and start()
        base = new File(getBaseDir());
        if (base.exists()) {
            Files.delete(base);
        }

        Files.mkdirs(base);
        Files.deleteOnExit(base);

        createDirectory(base, "conf");
        createDirectory(base, "lib");
        createDirectory(base, "logs");
        createDirectory(base, "temp");
        createDirectory(base, "work");
        createDirectory(base, "webapps");
    }

    public File getBase() {
        return base;
    }

    public void start() throws Exception {
        if (base == null || !base.exists()) {
            setup(configuration);
        }

        final Properties props = configuration.getProperties();
        if (props != null) {
            // inherit from system props
            final Properties properties = new Properties(System.getProperties());
            properties.putAll(configuration.getProperties());
            Logger.configure(properties);
        } else {
            Logger.configure();
        }

        final File conf = new File(base, "conf");
        final File webapps = new File(base, "webapps");

        final String catalinaBase = base.getAbsolutePath();

        // set the env before calling anoything on tomcat or Catalina!!
        System.setProperty("catalina.base", catalinaBase);
        System.setProperty("openejb.deployments.classpath", "false");
        System.setProperty("catalina.home", catalinaBase);
        System.setProperty("catalina.base", catalinaBase);
        System.setProperty("openejb.home", catalinaBase);
        System.setProperty("openejb.base", catalinaBase);
        System.setProperty("openejb.servicemanager.enabled", "false");

        copyFileTo(conf, "catalina.policy");
        copyTemplateTo(conf, "catalina.properties");
        copyFileTo(conf, "context.xml");
        copyFileTo(conf, "openejb.xml");
        copyFileTo(conf, "tomcat-users.xml");
        copyFileTo(conf, "web.xml");

        final boolean initialized;
        if (configuration.hasServerXml()) {
            final File file = new File(conf, "server.xml");
            final FileOutputStream fos = new FileOutputStream(file);
            try {
                IO.copy(configuration.getServerXmlFile(), fos);
            } finally {
                IO.close(fos);
            }

            // respect config (host/port) of the Configuration
            final QuickServerXmlParser ports = QuickServerXmlParser.parse(file);
            if (configuration.isKeepServerXmlAsThis()) {
                // force ports to be able to stop the server and get @ArquillianResource
                configuration.setHttpPort(Integer.parseInt(ports.http()));
                configuration.setStopPort(Integer.parseInt(ports.stop()));
            } else {
                final Map<String, String> replacements = new HashMap<String, String>();
                replacements.put(ports.http(), String.valueOf(configuration.getHttpPort()));
                replacements.put(ports.https(), String.valueOf(configuration.getHttpsPort()));
                replacements.put(ports.stop(), String.valueOf(configuration.getStopPort()));
                IO.copy(IO.slurp(new ReplaceStringsInputStream(IO.read(file), replacements)).getBytes(), file);
            }

            tomcat.server(createServer(file.getAbsolutePath()));
            initialized = true;
        } else {
            copyFileTo(conf, "server.xml");
            initialized = false;
        }

        if (props != null && !props.isEmpty()) {
            final FileWriter systemProperties = new FileWriter(new File(conf, "system.properties"));
            try {
                props.store(systemProperties, "");
            } finally {
                IO.close(systemProperties);
            }
        }

        // Need to use JULI so log messages from the tests are visible
        // using openejb logging conf in embedded mode
        /* if we use our config (Logger.configure()) don't override it
        copyFileTo(conf, "logging.properties");
        System.setProperty("java.util.logging.manager", "org.apache.juli.ClassLoaderLogManager");
        final File logging = new File(conf, "logging.properties");
        if (logging.exists()) {
            System.setProperty("java.util.logging.config.file", logging.getAbsolutePath());
        }
        */

        // Trigger loading of catalina.properties
        CatalinaProperties.getProperty("foo");

        tomcat.setBaseDir(base.getAbsolutePath());
        tomcat.setHostname(configuration.getHost());
        if (!initialized) {
            tomcat.getHost().setAppBase(webapps.getAbsolutePath());
            tomcat.getEngine().setDefaultHost(configuration.getHost());
            tomcat.setHostname(configuration.getHost());
        }

        if (tomcat.getRawConnector() == null && !configuration.isSkipHttp()) {
            final Connector connector = new Connector(Http11Protocol.class.getName());
            connector.setPort(configuration.getHttpPort());
            connector.setAttribute("connectionTimeout", "3000");
            tomcat.getService().addConnector(connector);
            tomcat.setConnector(connector);
        }

        // create https connector
        if (configuration.isSsl()) {
            final Connector httpsConnector = new Connector(Http11Protocol.class.getName());
            httpsConnector.setPort(configuration.getHttpsPort());
            httpsConnector.setSecure(true);
            httpsConnector.setProperty("SSLEnabled", "true");
            httpsConnector.setProperty("sslProtocol", configuration.getSslProtocol());

            if (configuration.getKeystoreFile() != null) {
                httpsConnector.setAttribute("keystoreFile", configuration.getKeystoreFile());
            }
            if (configuration.getKeystorePass() != null) {
                httpsConnector.setAttribute("keystorePass", configuration.getKeystorePass());
            }
            httpsConnector.setAttribute("keystoreType", configuration.getKeystoreType());
            httpsConnector.setAttribute("clientAuth", configuration.getClientAuth());
            httpsConnector.setAttribute("keyAlias", configuration.getKeyAlias());

            tomcat.getService().addConnector(httpsConnector);

            if (configuration.isSkipHttp()) {
                tomcat.setConnector(httpsConnector);
            }
        }

        // Bootstrap Tomcat
        Logger.getInstance(LogCategory.OPENEJB_STARTUP, Container.class).info("Starting TomEE from: " + base.getAbsolutePath()); // create it after Logger is configured

        if (configuration.getUsers() != null) {
            for (final Map.Entry<String, String> user : configuration.getUsers().entrySet()) {
                tomcat.addUser(user.getKey(), user.getValue());
            }
        }
        if (configuration.getRoles() != null) {
            for (final Map.Entry<String, String> user : configuration.getRoles().entrySet()) {
                for (final String role : user.getValue().split(" *, *")) {
                    tomcat.addRole(user.getKey(), role);
                }
            }
        }
        if (!initialized) {
            tomcat.init();
        }
        tomcat.start();

        // Bootstrap OpenEJB
        final Properties properties = new Properties();
        properties.setProperty("openejb.deployments.classpath", "false");
        properties.setProperty("openejb.loader", "tomcat-system");
        properties.setProperty("openejb.home", catalinaBase);
        properties.setProperty("openejb.base", catalinaBase);
        properties.setProperty("openejb.servicemanager.enabled", "false");
        if (configuration.getProperties() != null) {
            properties.putAll(configuration.getProperties());
        }
        if (properties.getProperty("openejb.system.apps") == null) { // will make startup faster and it is rarely useful for embedded case
            properties.setProperty("openejb.system.apps", "false");
        }
        if (configuration.isQuickSession()) {
            properties.put("openejb.session.manager", QuickSessionManager.class.getName());
        }

        try {
            final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            final Properties tomcatServerInfo = IO.readProperties(classLoader.getResourceAsStream("org/apache/catalina/util/ServerInfo.properties"), new Properties());

            String serverNumber = tomcatServerInfo.getProperty("server.number");
            if (serverNumber == null) {
                // Tomcat5 only has server.info
                final String serverInfo = tomcatServerInfo.getProperty("server.info");
                if (serverInfo != null) {
                    final int slash = serverInfo.indexOf('/');
                    serverNumber = serverInfo.substring(slash + 1);
                }
            }
            if (serverNumber != null) {
                System.setProperty("tomcat.version", serverNumber);
            }

            final String serverBuilt = tomcatServerInfo.getProperty("server.built");
            if (serverBuilt != null) {
                System.setProperty("tomcat.built", serverBuilt);
            }
        } catch (final Throwable e) {
            // no-op
        }

        final TomcatLoader loader = new TomcatLoader();
        loader.initDefaults(properties);

        // need to add properties after having initialized defaults
        // to properties passed to SystemInstance otherwise we loose some of them
        final Properties initProps = new Properties();
        initProps.putAll(System.getProperties());
        initProps.putAll(properties);
        if (SystemInstance.isInitialized()) {
            SystemInstance.get().getProperties().putAll(initProps);
        } else {
            SystemInstance.init(initProps);
        }
        SystemInstance.get().setComponent(StandardServer.class, (StandardServer) tomcat.getServer());
        SystemInstance.get().setComponent(Server.class, tomcat.getServer()); // needed again cause of init()

        loader.initialize(properties);

        assembler = SystemInstance.get().getComponent(Assembler.class);
        configurationFactory = new ConfigurationFactory();
    }

    private static Server createServer(final String serverXml) {
        final Catalina catalina = new Catalina() {
            // skip few init we don't need *here*
            @Override
            protected void initDirs() {
                // no-op
            }

            @Override
            protected void initStreams() {
                // no-op
            }

            @Override
            protected void initNaming() {
                // no-op
            }
        };
        catalina.setConfigFile(serverXml);
        catalina.load();
        return catalina.getServer();
    }

    public ConfigurationFactory getConfigurationFactory() {
        return configurationFactory;
    }

    private String getBaseDir() {
        File file;
        try {

            final String dir = configuration.getDir();
            if (dir != null) {
                final File dirFile = new File(dir);
                if (dirFile.exists()) {
                    return dir;
                }
                return Files.mkdir(dirFile).getAbsolutePath();
            }

            try {
                final File target = new File("target");
                file = File.createTempFile("apache-tomee", "-home", target.exists() ? target : null);
            } catch (final Exception e) {

                final File tmp = new File(configuration.getTempDir());
                if (!tmp.exists() && !tmp.mkdirs()) {
                    throw new IOException("Failed to create local tmp directory: " + tmp.getAbsolutePath());
                }

                file = File.createTempFile("apache-tomee", "-home", tmp);
            }

            return file.getAbsolutePath();

        } catch (final IOException e) {
            throw new TomEERuntimeException("Failed to get or create base dir: " + configuration.getDir(), e);
        }
    }

    public void stop() throws Exception {

        final Connector connector = tomcat.getConnector();
        if (null != connector) {
            connector.stop();
        }

        try {
            tomcat.stop();
        } catch (final LifecycleException e) {
            e.printStackTrace();
        }
        try {
            tomcat.destroy();
        } catch (final LifecycleException e) {
            e.printStackTrace();
        }
        try {
            deleteTree(base);
        } catch (final Exception e) {
            e.printStackTrace();
        }

        OpenEJB.destroy();
        // don't set base = null here to be able to use base after to clean up from outside of this class
    }

    @SuppressWarnings("UnusedDeclaration")
    public AppContext deploy(final String name, final File file) throws OpenEJBException, IOException, NamingException {
        return deploy(name, file, false);
    }

    public AppContext deploy(final String name, final File file, final boolean overrideName) throws OpenEJBException, IOException, NamingException {

        final AppContext context;
        final AppInfo appInfo;

        if (WebAppDeployer.Helper.isWebApp(file)) {
            String contextRoot = file.getName();
            if (overrideName) {
                contextRoot = name;
            }

            appInfo = SystemInstance.get().getComponent(WebAppDeployer.class).deploy(null, contextRoot, file);

            if (appInfo != null) {
                context = SystemInstance.get().getComponent(ContainerSystem.class).getAppContext(appInfo.appId);
            } else {
                context = null;
            }
        } else {
            appInfo = configurationFactory.configureApplication(file);
            if (overrideName) {
                appInfo.appId = name;
                for (final EjbJarInfo ejbJar : appInfo.ejbJars) {
                    if (file.getName().equals(ejbJar.moduleName)) {
                        ejbJar.moduleName = name;
                        ejbJar.moduleId = name;
                    }
                    for (final EnterpriseBeanInfo ejb : ejbJar.enterpriseBeans) {
                        if (BeanContext.Comp.openejbCompName(file.getName()).equals(ejb.ejbName)) {
                            ejb.ejbName = BeanContext.Comp.openejbCompName(name);
                        }
                    }
                }
                for (final WebAppInfo webApp : appInfo.webApps) {
                    if (sameApplication(file, webApp)) {
                        webApp.moduleId = name;
                        webApp.contextRoot = lastPart(name, webApp.contextRoot);
                        if ("ROOT".equals(webApp.contextRoot)) {
                            webApp.contextRoot = "";
                        }
                    }
                }
            }

            context = assembler.createApplication(appInfo);
        }

        moduleIds.put(name, null != appInfo ? appInfo.path : null);
        infos.put(name, appInfo);
        appContexts.put(name, context);

        return context;
    }

    @SuppressWarnings("UnusedDeclaration")
    public AppInfo getInfo(final String name) {
        return infos.get(name);
    }

    public void undeploy(final String name) throws UndeployException, NoSuchApplicationException {
        final String moduleId = moduleIds.remove(name);
        infos.remove(name);
        appContexts.remove(name);
        if (moduleId != null) {
            assembler.destroyApplication(moduleId);
        }
    }

    public Context getJndiContext() {
        return assembler.getContainerSystem().getJNDIContext();
    }

    public AppContext getAppContexts(final String moduleId) {
        return appContexts.get(moduleId);
    }

    private void deleteTree(final File file) {
        if (file == null) {
            return;
        }
        if (!file.exists()) {
            return;
        }

        if (file.isFile()) {
            if (!file.delete()) {
                file.deleteOnExit();
            }
            return;
        }

        if (file.isDirectory()) {
            if ("".equals(file.getName())) {
                return;
            }
            if ("src/main".equals(file.getName())) {
                return;
            }

            final File[] children = file.listFiles();

            if (children != null) {
                for (final File child : children) {
                    deleteTree(child);
                }
            }

            if (!file.delete()) {
                file.deleteOnExit();
            }
        }
    }

    private void copyTemplateTo(final File targetDir, final String filename) throws Exception {
        // don't break apps using Velocity facade
        final VelocityEngine engine = new VelocityEngine();
        engine.setProperty(Velocity.RUNTIME_LOG_LOGSYSTEM, new NullLogChute());
        engine.setProperty(Velocity.RESOURCE_LOADER, "class");
        engine.setProperty("class.resource.loader.description", "Velocity Classpath Resource Loader");
        engine.setProperty("class.resource.loader.class", ClasspathResourceLoader.class.getName());
        engine.init();
        final Template template = engine.getTemplate("/org/apache/tomee/configs/" + filename);
        final VelocityContext context = new VelocityContext();
        context.put("tomcatHttpPort", Integer.toString(configuration.getHttpPort()));
        context.put("tomcatShutdownPort", Integer.toString(configuration.getStopPort()));
        final Writer writer = new FileWriter(new File(targetDir, filename));
        template.merge(context, writer);
        writer.flush();
        writer.close();
    }

    private void copyFileTo(final File targetDir, final String filename) throws IOException {
        final InputStream is = getClass().getResourceAsStream("/org/apache/tomee/configs/" + filename);
        if (is != null) { // should be null since we are using default conf
            try {
                IO.copy(is, new File(targetDir, filename));
            } finally {
                IO.close(is);
            }
        }
    }

    private File createDirectory(final File parent, final String directory) {
        final File dir = new File(parent, directory);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Unable to make dir " + dir.getAbsolutePath());
        }

        return dir;
    }

    public Tomcat getTomcat() {
        return tomcat;
    }

    public void await() {
        tomcat.getServer().await();
    }

    @Override
    public void close() {
        final CountDownLatch end = new CountDownLatch(1);
        final Container container = Container.this;
        new Thread() {
            {
                setName("tomee-embedded-await-" + hashCode());
            }

            @Override
            public void run() {
                try {
                    container.await();
                    end.countDown();
                } catch (final Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }.start();

        try {
            stop();
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to stop container", e);
        }

        try {
            end.await();
        } catch (final InterruptedException e) {
            Thread.interrupted();
        }
    }

    public org.apache.catalina.Context addContext(final String context, final String path) {
        final File root = new File(path);
        if (!root.exists()) {
            Files.mkdirs(root);
        }
        return getTomcat().addContext(context, root.getAbsolutePath()); // we don't want to be relative
    }

    public Container inject(final Object instance) {
        Injector.inject(instance);
        return this;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    private static class InternalTomcat extends Tomcat {
        private void server(final Server s) {
            server = s;
            if (service == null) {
                final Service[] services = server.findServices();
                if (services.length > 0) {
                    service = services[0];
                    if (service.getContainer() != null) {
                        engine = Engine.class.cast(service.getContainer());
                        final org.apache.catalina.Container[] hosts = engine.findChildren();
                        if (hosts.length > 0) {
                            host = Host.class.cast(hosts[0]);
                        }
                    }
                }
                if (service.findConnectors().length > 0) {
                    connector = service.findConnectors()[0];
                }
            }
        }

        public Connector getRawConnector() {
            return connector;
        }
    }

    private static class TomcatWithFastSessionIDs extends InternalTomcat {

        @Override
        public void start() throws LifecycleException {
            // Use fast, insecure session ID generation for all tests
            final Server server = getServer();
            for (final Service service : server.findServices()) {
                final org.apache.catalina.Container e = service.getContainer();
                for (final org.apache.catalina.Container h : e.findChildren()) {
                    for (final org.apache.catalina.Container c : h.findChildren()) {
                        Manager m = ((org.apache.catalina.Context) c).getManager();
                        if (m == null) {
                            m = new StandardManager();
                            ((org.apache.catalina.Context) c).setManager(m);
                        }
                        if (m instanceof ManagerBase) {
                            ((ManagerBase) m).setSecureRandomClass(
                                    "org.apache.catalina.startup.FastNonSecureRandom");
                        }
                    }
                }
            }
            super.start();
        }
    }

    private static class ContainerFilter implements Filter {
        private final String[] forced;
        private final String[] skipped;

        public ContainerFilter(final Properties properties) {
            final String forcedStr = properties == null ? null : properties.getProperty("openejb.classloader.forced-load");
            forced = forcedStr != null ? forcedStr.split(" *, *") : null;
            final String skippedStr = properties == null ? null : properties.getProperty("openejb.classloader.forced-skip");
            skipped = skippedStr != null ? skippedStr.split(" *, *") : null;
        }

        @Override
        public boolean accept(final String name) {
            return !isContainer(forced, skipped, name);
        }
    }
}
