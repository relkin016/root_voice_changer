package ru.relkin.rootvoice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;

public class EffectsXmlPatcherTest {
    private static final String UUID = "f6f24dc9-fd4d-4a3c-845b-581ddf0637b6";

    // Сокращённый /vendor/etc/audio_effects.xml из AOSP: preprocess закомментирован.
    private static final String AOSP = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<audio_effects_conf version=\"2.0\" xmlns=\"http://schemas.android.com/audio/audio_effects_conf/v2_0\">\n"
            + "    <libraries>\n"
            + "        <library name=\"bundle\" path=\"libbundlewrapper.so\"/>\n"
            + "        <library name=\"pre_processing\" path=\"libaudiopreprocessing.so\"/>\n"
            + "    </libraries>\n"
            + "    <effects>\n"
            + "        <effect name=\"bassboost\" library=\"bundle\" uuid=\"8631f300-72e2-11df-b57e-0002a5d5c51b\"/>\n"
            + "        <effect name=\"aec\" library=\"pre_processing\" uuid=\"bb392ec0-8d4d-11e0-a896-0002a5d5c51b\"/>\n"
            + "        <effect name=\"ns\" library=\"pre_processing\" uuid=\"c06c8400-8e06-11e0-9cb6-0002a5d5c51b\"/>\n"
            + "    </effects>\n"
            + "    <!-- Audio pre processor configurations.\n"
            + "    <preprocess>\n"
            + "        <stream type=\"voice_communication\">\n"
            + "            <apply effect=\"aec\"/>\n"
            + "        </stream>\n"
            + "    </preprocess>\n"
            + "    -->\n"
            + "</audio_effects_conf>\n";

    private static final String WITH_PRE = "<audio_effects_conf version=\"2.0\">\n"
            + "  <libraries><library name=\"pre_processing\" path=\"libaudiopreprocessing.so\"/></libraries>\n"
            + "  <effects><effect name=\"aec\" library=\"pre_processing\" uuid=\"bb392ec0-8d4d-11e0-a896-0002a5d5c51b\"/></effects>\n"
            + "  <postprocess><stream type=\"music\"><apply effect=\"aec\"/></stream></postprocess>\n"
            + "  <preprocess>\n"
            + "    <stream type=\"voice_communication\">\n"
            + "      <apply effect=\"aec\"/>\n"
            + "    </stream>\n"
            + "    <stream type = \"mic\"/>\n"
            + "  </preprocess>\n"
            + "</audio_effects_conf>\n";

    private static Document parse(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static Element stream(Document d, String type) {
        NodeList pre = d.getElementsByTagName("preprocess");
        assertEquals(1, pre.getLength());
        NodeList streams = ((Element) pre.item(0)).getElementsByTagName("stream");
        Element found = null;
        for (int i = 0; i < streams.getLength(); i++) {
            Element s = (Element) streams.item(i);
            if (s.getAttribute("type").equals(type)) {
                assertEquals("дубликат stream " + type, null, found);
                found = s;
            }
        }
        return found;
    }

    private static boolean applies(Element stream, String effect) {
        NodeList a = stream.getElementsByTagName("apply");
        for (int i = 0; i < a.getLength(); i++)
            if (((Element) a.item(i)).getAttribute("effect").equals(effect)) return true;
        return false;
    }

    private static void assertPatched(Document d) {
        NodeList libs = d.getElementsByTagName("library");
        boolean lib = false;
        for (int i = 0; i < libs.getLength(); i++) {
            Element l = (Element) libs.item(i);
            lib |= l.getAttribute("name").equals("rootvoice") && l.getAttribute("path").equals("librootvoice.so");
        }
        assertTrue(lib);
        NodeList effects = d.getElementsByTagName("effect");
        boolean eff = false;
        for (int i = 0; i < effects.getLength(); i++) {
            Element e = (Element) effects.item(i);
            eff |= e.getAttribute("name").equals("rootvoice") && e.getAttribute("uuid").equals(UUID);
        }
        assertTrue(eff);
        for (String src : EffectsXmlPatcher.SOURCES) assertTrue(src, applies(stream(d, src), "rootvoice"));
    }

    @Test
    public void addsPreprocessWhenOnlyCommentedOut() throws Exception {
        String out = EffectsXmlPatcher.patch(AOSP, UUID);
        Document d = parse(out);
        assertPatched(d);
        assertTrue(out.contains("libbundlewrapper.so"));
        assertFalse(applies(stream(d, "voice_communication"), "aec"));
    }

    @Test
    public void appendsToExistingStreamsKeepingTheirEffects() throws Exception {
        Document d = parse(EffectsXmlPatcher.patch(WITH_PRE, UUID));
        assertPatched(d);
        assertTrue(applies(stream(d, "voice_communication"), "aec"));
        // postprocess не тронут
        Element post = (Element) d.getElementsByTagName("postprocess").item(0);
        assertEquals(1, post.getElementsByTagName("apply").getLength());
    }

    @Test
    public void idempotent() {
        String once = EffectsXmlPatcher.patch(WITH_PRE, UUID);
        assertEquals(once, EffectsXmlPatcher.patch(once, UUID));
    }

    @Test
    public void emptySections() throws Exception {
        String xml = "<audio_effects_conf version=\"2.0\"><libraries/><effects/></audio_effects_conf>";
        assertPatched(parse(EffectsXmlPatcher.patch(xml, UUID)));
    }

    @Test
    public void paramsFitIntoSystemProperty() {
        VoiceParams p = new VoiceParams();
        p.pitch = -12;
        p.rough = -1;
        p.robot = 1;
        p.echo = 1;
        p.radio = true;
        p.gainDb = -12;
        String s = p.serialize();
        assertTrue(s, s.length() < 92);
        assertEquals("1 1 -12.00 -1.000 1.000 1.000 1 -12.0", s);
    }

    @Test
    public void modulePaths() {
        assertEquals("system/vendor/etc/audio_effects.xml", ModuleInstaller.moduleRelPath("/vendor/etc/audio_effects.xml"));
        assertEquals("system/etc/audio_effects.xml", ModuleInstaller.moduleRelPath("/system/etc/audio_effects.xml"));
        assertEquals("odm/etc/audio_effects.xml", ModuleInstaller.moduleRelPath("/odm/etc/audio_effects.xml"));
    }
}
