/*******************************************************************************
 * Copyright (c) 2017, 2023 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.jdbc.fat.loadfromapp;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.util.List;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.EnterpriseArchive;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.runner.RunWith;

import com.ibm.websphere.simplicity.ShrinkHelper;

import componenttest.annotation.Server;
import componenttest.annotation.TestServlet;
import componenttest.annotation.TestServlets;
import componenttest.custom.junit.runner.FATRunner;
import componenttest.rules.repeater.JakartaEE10Action;
import componenttest.rules.repeater.JakartaEE9Action;
import componenttest.rules.repeater.RepeatTests;
import componenttest.topology.impl.LibertyServer;
import componenttest.topology.utils.FATServletClient;
import web.derby.DerbyLoadFromAppServlet;
import web.other.LoadFromAppServlet;

/**
 * This test bucket will cover JDBC drivers being loaded from the application
 * instead of from libraries configured in server.xml.
 */
@RunWith(FATRunner.class)
public class JDBCLoadFromAppTest extends FATServletClient {
    @ClassRule
    public static RepeatTests r = RepeatTests
                    .withoutModification()
                    .andWith(new JakartaEE9Action())
                    .andWith(new JakartaEE10Action());

    @Server("com.ibm.ws.jdbc.fat.loadfromapp")
    @TestServlets(value = {
                            @TestServlet(servlet = DerbyLoadFromAppServlet.class, path = "derbyApp/DerbyLoadFromAppServlet"),
                            @TestServlet(servlet = LoadFromAppServlet.class, path = "otherApp/LoadFromAppServlet")
    })
    public static LibertyServer server;

    @BeforeClass
    public static void setUp() throws Exception {
        WebArchive derbyApp = ShrinkWrap.create(WebArchive.class, "derbyApp.war")
                        .addPackage("web.derby") //
                        .addAsWebInfResource(new File("test-applications/derbyapp/resources/WEB-INF/ibm-ejb-jar-bnd.xml"))
                        .addAsWebInfResource(new File("test-applications/derbyapp/resources/WEB-INF/ibm-web-bnd.xml"))
                        .addAsLibrary(new File("publish/shared/resources/derby/derby.jar")) //
                        .addPackage("jdbc.driver.proxy"); // delegates to the Derby JDBC driver
        ShrinkHelper.exportAppToServer(server, derbyApp);

        WebArchive otherWAR = ShrinkWrap.create(WebArchive.class, "otherApp.war")
                        .addAsWebInfResource(new File("test-applications/otherapp/resources/WEB-INF/ibm-web-bnd.xml"))
                        .addPackage("web.other") //
                        .addPackage("jdbc.driver.mini") // barely usable, fake jdbc driver included in app
                        .addPackage("jdbc.driver.mini.jse") // java.sql.Driver implementation for the above
                        .addAsServiceProvider(java.sql.Driver.class, jdbc.driver.mini.jse.DriverImpl.class)
                        .addPackage("jdbc.driver.proxy"); // delegates to the "mini" JDBC driver

        JavaArchive ejb1JAR = ShrinkWrap.create(JavaArchive.class, "ejb1.jar")
                        .addAsManifestResource(new File("test-applications/otherapp/resources/ejb.first/META-INF/ibm-ejb-jar-bnd.xml"))
                        .addPackage("ejb.first");

        JavaArchive ejb2JAR = ShrinkWrap.create(JavaArchive.class, "ejb2.jar")
                        .addAsManifestResource(new File("test-applications/otherapp/resources/ejb.second/META-INF/ibm-ejb-jar-bnd.xml"))
                        .addPackage("ejb.second");

        JavaArchive lmJAR = ShrinkWrap.create(JavaArchive.class, "top-level-login-modules.jar")
                        .addPackage("loginmod");

        EnterpriseArchive otherApp = ShrinkWrap.create(EnterpriseArchive.class, "otherApp.ear")
                        .addAsModule(otherWAR)
                        .addAsModule(ejb1JAR)
                        .addAsModule(ejb2JAR)
                        .addAsLibrary(lmJAR);

        ShrinkHelper.exportAppToServer(server, otherApp);

        server.addInstalledAppForValidation("derbyApp");
        server.addInstalledAppForValidation("otherApp");

        server.startServer();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        try {
            // Must find exactly 1 warning for connection leaks after running tests:
            List<String> connectionLeakWarnings = server.findStringsInLogs("J2CA8070I");
            assertEquals(connectionLeakWarnings.toString(), 1, connectionLeakWarnings.size());
        } finally {
            server.stopServer("CWWKS1148E(?=.*LoadFromAppLoginModule)(?=.*derbyApp)", // Intentional error: loginmod.LoadFromAppLoginModule not found in derbyApp
                              "CWWKS1148E(?=.*LoadFromWebAppLoginModule)(?=.*otherApp)", // Intentional error: web.derby.LoadFromWebAppLoginModule not found in otherApp
                              "SRVE9967W.*derbyLocale" // ignore missing Derby locales
            );
        }
    }
}
