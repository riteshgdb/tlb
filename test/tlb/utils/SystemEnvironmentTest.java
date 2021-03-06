package tlb.utils;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertThat;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.Map;

public class SystemEnvironmentTest {
    
    @Test
    public void shouldGetPropertyAvailableInGivenMap() throws Exception {
        HashMap<String, String> map = new HashMap<String, String>();
        map.put("foo", "bar");
        SystemEnvironment env = new SystemEnvironment(map);
        assertThat(env.val("foo"), is("bar"));
    }

    @Test
    public void shouldRecursivelyResolveVariables() throws Exception {
        HashMap<String, String> map = new HashMap<String, String>();
        map.put("foo", "bar");
        map.put("bar", "oo");
        map.put("baz", "baz-${foo}");
        map.put("quux", "baz-${f${bar}}");
        map.put("complex", "${quux}|${q${bang}}");
        map.put("bang", "u${boom}");
        map.put("boom", "u${axe}");
        map.put("axe", "${X}");
        map.put("X", "x");
        SystemEnvironment env = new SystemEnvironment(map);
        assertThat(env.val("foo"), is("bar"));
        assertThat(env.val("baz"), is("baz-bar"));
        assertThat(env.val("quux"), is("baz-bar"));
        assertThat(env.val("complex"), is("baz-bar|baz-bar"));
    }

    @Test
    public void shouldNotFailForTemplateCharactersAppearingWhileResolvingVariables() throws Exception {
        HashMap<String, String> map = new HashMap<String, String>();
        map.put("fo$o", "ba${r");
        map.put("bar", "$o");
        map.put("baz", "baz-${fo${bar}}");
        SystemEnvironment env = new SystemEnvironment(map);
        assertThat(env.val("baz"), is("baz-ba${r"));
    }

    @Test
    public void shouldGetSystemEnvironmentVairableWhenNoMapPassed() throws Exception{
        SystemEnvironment env = new SystemEnvironment();
        assertThat(env.val("HOME"), is(System.getProperty("user.home")));
    }
    
    @Test
    public void shouldDefaultEnvVariableValues() {
        HashMap<String, String> map = new HashMap<String, String>();
        SystemEnvironment env = new SystemEnvironment(map);
        assertThat(env.val("foo", "bar"), is("bar"));
        assertThat(env.val("foo"), is(nullValue()));
        map.put("foo", "baz");
        env = new SystemEnvironment(map);
        assertThat(env.val("foo", "bar"), is("baz"));
        assertThat(env.val("foo"), is("baz"));
    }

    @Test
    public void shouldGenerateADigestOfVariablesSet() throws IOException {
        HashMap<String, String> env = new HashMap<String, String>();
        env.put("foo", "bar");
        SystemEnvironment sysEnv = new SystemEnvironment(env);
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(os);
        oos.writeObject(env);
        assertThat(sysEnv.getDigest(), is(DigestUtils.md5Hex(os.toByteArray())));

        sysEnv = new SystemEnvironment();
        os = new ByteArrayOutputStream();
        oos = new ObjectOutputStream(os);
        Map<String,String> externalEnv = System.getenv();
        Map<String, String> serializableEnv = new HashMap<String, String>();
        for (String externalKey : externalEnv.keySet()) {
            serializableEnv.put(externalKey, externalEnv.get(externalKey));
        }
        oos.writeObject(serializableEnv);
        assertThat(sysEnv.getDigest(), is(DigestUtils.md5Hex(os.toByteArray())));
    }

    @Test
    public void shouldUnderstandTmpDir() {
        HashMap<String, String> env = new HashMap<String, String>();
        env.put("foo", "bar");
        SystemEnvironment sysEnv = new SystemEnvironment(env);
        assertThat(sysEnv.tmpDir(), is(System.getProperty("java.io.tmpdir") + "/" + sysEnv.getDigest()));
    }
}
