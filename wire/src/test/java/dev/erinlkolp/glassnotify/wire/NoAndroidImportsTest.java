package dev.erinlkolp.glassnotify.wire;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * The wire module must stay free of Android types so it can be tested on the
 * host JVM at full speed. A stray android.* import would compile fine here and
 * only fail much later, so check it directly.
 */
public class NoAndroidImportsTest {

    @Test
    public void noSourceFileImportsAndroid() throws IOException {
        File sourceRoot = new File("src/main/java");
        assertTrue("expected to run with the module as working directory", sourceRoot.isDirectory());

        List<String> offenders = new ArrayList<String>();
        collect(sourceRoot, offenders);

        if (!offenders.isEmpty()) {
            fail("android imports found in wire: " + offenders);
        }
    }

    private void collect(File dir, List<String> offenders) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collect(child, offenders);
            } else if (child.getName().endsWith(".java")) {
                BufferedReader reader = new BufferedReader(new FileReader(child));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("import android.")
                                || trimmed.startsWith("import androidx.")) {
                            offenders.add(child.getName() + ": " + trimmed);
                        }
                    }
                } finally {
                    reader.close();
                }
            }
        }
    }
}
