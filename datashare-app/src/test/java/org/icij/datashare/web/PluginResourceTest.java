package org.icij.datashare.web;

import org.icij.datashare.PluginService;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.session.LocalUserFilter;
import org.icij.datashare.web.testhelpers.AbstractProdWebServerTest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;

import static java.net.URLEncoder.encode;
import static org.fest.assertions.Assertions.assertThat;

public class PluginResourceTest extends AbstractProdWebServerTest {
    @Rule public TemporaryFolder pluginFolder = new TemporaryFolder();
    @Test
    public void test_list_plugins() {
        get("/api/plugins").should().respond(200).
                haveType("application/json").
                contain("my-plugin").
                contain("my-other-plugin");
    }

    @Test
    public void test_list_plugins_with_regexp() {
        get("/api/plugins?filter=.*other.*").
                should().respond(200).contain("my-other-plugin").
                should().not().contain("my-plugin");
    }

    @Test
    public void test_install_plugin_by_id() {
        put("/api/plugins/install?id=my-plugin").should().respond(200);
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").toFile()).exists();
    }

    @Test
    public void test_install_plugin_by_url() throws UnsupportedEncodingException {
        put("/api/plugins/install?url=" + encode(ClassLoader.getSystemResource("my-plugin.tgz").toString(), "utf-8")).should().respond(200);
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").toFile()).exists();
    }

    @Test
    public void test_install_plugin_with_no_parameter() {
        put("/api/plugins/install").should().respond(400);
    }

    @Test
    public void test_install_unknown_plugin() {
        put("/api/plugins/install/unknown_id").should().respond(404);
    }

    @Test
    public void test_uninstall_plugin() {
        put("/api/plugins/install?id=my-plugin").should().respond(200);
        delete("/api/plugins/uninstall?id=my-plugin").should().respond(200);
        assertThat(pluginFolder.getRoot().toPath().resolve("my-plugin").toFile()).doesNotExist();
    }

    @Test
    public void test_uninstall_unknown_plugin() {
        delete("/api/plugins/uninstall?id=unknown_id").should().respond(404);
    }

    @Before
    public void setUp() {
        configure(routes -> routes.add(new PluginResource(new PluginService(pluginFolder.getRoot().toPath(), new ByteArrayInputStream(("{\"pluginList\": [" +
        "{\"id\":\"my-plugin\", \"url\": \"" + ClassLoader.getSystemResource("my-plugin.tgz")+ "\"}," +
        "{\"id\":\"my-other-plugin\", \"url\": \"https://dummy.url\"}" +
        "]}").getBytes())))).filter(new LocalUserFilter(new PropertiesProvider())));
    }
}
