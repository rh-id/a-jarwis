package m.co.rh.id.a_jarwis.base.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.Serializable;

@RunWith(AndroidJUnit4.class)
public class SerializeUtilsTest {

    @Test
    public void serializeTest() {
        TestBean testBean = new TestBean("jarwis", 11);
        byte[] bytes = SerializeUtils.serialize(testBean);
        TestBean result = SerializeUtils.deserialize(bytes);
        assertEquals(testBean.name, result.name);
        assertEquals(testBean.version, result.version);
    }

    @Test
    public void deserializeTest_invalidBytes() {
        assertThrows(RuntimeException.class,
                () -> SerializeUtils.deserialize(new byte[]{1, 9, 8, 4}));
    }

    private static class TestBean implements Serializable {
        private final String name;
        private final int version;

        private TestBean(String name, int version) {
            this.name = name;
            this.version = version;
        }
    }
}
