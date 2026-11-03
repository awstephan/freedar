"""Check the archive and Maven edge cases used by the notice collector."""

import io
from pathlib import Path
import tempfile
import unittest
import zipfile

from generate import archive_notices, pom_licenses


def archive(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as result:
        for name, data in entries.items():
            result.writestr(name, data)
    return output.getvalue()


class NoticeCollectionTest(unittest.TestCase):
    def test_preserves_nested_runtime_notices_without_shipping_lint_licenses(self):
        aar = archive({
            "classes.jar": archive({"META-INF/NOTICE": "runtime notice"}),
            "libs/helper.jar": archive({"LICENSE.txt": "helper license"}),
            "lint.jar": archive({"LICENSE.txt": "build-tool license"}),
            "META-INF/library/LICENSE.txt": "library license",
            "res/drawable/license_icon.xml": "not a license",
        })
        # License-looking resource names should not be interpreted as legal text.
        found = dict(archive_notices(aar))
        self.assertEqual(found["classes.jar!/META-INF/NOTICE"], "runtime notice")
        self.assertEqual(found["libs/helper.jar!/LICENSE.txt"], "helper license")
        self.assertEqual(found["META-INF/library/LICENSE.txt"], "library license")
        self.assertFalse(any("lint.jar" in name for name in found))
        self.assertNotIn("res/drawable/license_icon.xml", found)

    def test_maven_license_inheritance(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            parent = cache / "example" / "parent" / "1" / "hash" / "parent.pom"
            parent.parent.mkdir(parents=True)
            parent.write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0">
                <licenses><license><name>Apache 2.0</name></license></licenses>
                </project>''')
            child = cache / "child.pom"
            child.write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0">
                <parent><groupId>example</groupId><artifactId>parent</artifactId>
                <version>1</version></parent></project>''')
            self.assertEqual(pom_licenses(child, cache), ["Apache 2.0"])


if __name__ == "__main__":
    unittest.main()
