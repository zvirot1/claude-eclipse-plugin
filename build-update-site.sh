#!/bin/bash
# Build Eclipse plugin JAR + P2 update site for installation via Help -> Install New Software
# pipefail: otherwise `javac ... | tail -5` masks javac's exit code and the
# build continues to package a half-compiled JAR (we've been bitten by this).
set -eo pipefail

cd "$(dirname "$0")"
PROJECT_DIR="$(pwd)"

# ==================== Configuration ====================
TIMESTAMP="${1:-$(date +%Y%m%d%H%M)}"
VERSION="1.0.0.${TIMESTAMP}"
ECLIPSE_TARGET="4.34"
PLUGIN_ID="com.anthropic.eclipse.claude"
FEATURE_ID="${PLUGIN_ID}.feature"
ECLIPSE_PLUGINS_DIR="C:/eclipse/plugins"

echo "Building version: ${VERSION}"

# ==================== Step 1: Compile Java sources ====================
echo "[1/5] Compiling Java sources..."
CP=""
for jar in "$ECLIPSE_PLUGINS_DIR"/org.eclipse.swt_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.swt.win32.win32.x86_64_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.jface_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.ui_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.ui.workbench_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.core.runtime_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.core.resources_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.core.jobs_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.core.commands_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.equinox.common_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.equinox.registry_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.equinox.preferences_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.equinox.security_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.osgi_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.ui.editors_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.ui.workbench.texteditor_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.ui.ide_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.jface.text_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.text_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.compare_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.jdt.core_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.e4.ui.model.workbench_*.jar \
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.e4.ui.workbench_*.jar; do
    [ -f "$jar" ] && CP="$CP;$jar"
done

rm -rf bin && mkdir -p bin
javac -source 11 -target 11 -cp "$CP" -d bin -sourcepath src $(find src -name "*.java") 2>&1 | grep -E 'error|warning|^[0-9]+ error' || true
# Ensure javac actually succeeded (exit code propagated via pipefail above).
# If bin ends up essentially empty, abort — we'd otherwise ship a broken JAR.
CLASS_COUNT=$(find bin -name '*.class' 2>/dev/null | wc -l)
if [ "$CLASS_COUNT" -lt 100 ]; then
    echo "ERROR: javac produced only $CLASS_COUNT class files — compilation failed. Aborting." >&2
    exit 1
fi

# ==================== Step 2: Update MANIFEST.MF version ====================
echo "[2/5] Updating manifest version..."
sed -i "s/^Bundle-Version: .*/Bundle-Version: ${VERSION}/" META-INF/MANIFEST.MF

# ==================== Step 3: Build plugin JAR ====================
echo "[3/5] Packaging plugin JAR..."
PLUGIN_JAR="build/${PLUGIN_ID}_${VERSION}.jar"
mkdir -p build
rm -f "$PLUGIN_JAR"
jar cfm "$PLUGIN_JAR" META-INF/MANIFEST.MF -C bin . -C . plugin.xml icons webview
echo "  -> $PLUGIN_JAR ($(stat -c%s "$PLUGIN_JAR") bytes)"

# ==================== Step 4: Build Feature JAR ====================
echo "[4/5] Building feature JAR..."
FEATURE_WORK="build/feature-work"
rm -rf "$FEATURE_WORK" && mkdir -p "$FEATURE_WORK/META-INF"

cat > "$FEATURE_WORK/feature.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<feature id="${FEATURE_ID}" label="Claude AI Eclipse Plugin (Independent Build)"
         version="${VERSION}" provider-name="Independent Build (Unaffiliated)">
   <description url="https://github.com/zvirot1/claude-eclipse-plugin">Independent third-party Eclipse plug-in that wraps the official Claude Code CLI. Not produced by, affiliated with, endorsed by or supported by Anthropic. "Claude" and "Claude Code" are trademarks of Anthropic, PBC.</description>
   <copyright>Independent build — not an Anthropic product. "Claude" and "Claude Code" are trademarks of Anthropic, PBC.</copyright>
   <license>Provided as-is with no warranty. No support contract or SLA implied.</license>
   <plugin id="${PLUGIN_ID}" download-size="0" install-size="0" version="${VERSION}" unpack="false"/>
</feature>
EOF

cat > "$FEATURE_WORK/META-INF/MANIFEST.MF" <<EOF
Manifest-Version: 1.0
Created-By: build-update-site.sh

EOF

FEATURE_JAR="build/${FEATURE_ID}_${VERSION}.jar"
rm -f "$FEATURE_JAR"
(cd "$FEATURE_WORK" && jar cfM "../../$FEATURE_JAR" META-INF/MANIFEST.MF feature.xml)
echo "  -> $FEATURE_JAR ($(stat -c%s "$FEATURE_JAR") bytes)"

# ==================== Step 5: Build P2 Update Site (using Eclipse p2 publisher) ====================
echo "[5/5] Building P2 update site via Eclipse p2 publisher..."
SITE_DIR="build/update-site"
SITE_ABS="$(pwd)/$SITE_DIR"
rm -rf "$SITE_DIR"
mkdir -p "$SITE_DIR/plugins" "$SITE_DIR/features"
cp "$PLUGIN_JAR" "$SITE_DIR/plugins/"
cp "$FEATURE_JAR" "$SITE_DIR/features/"

# category.xml at site root (referenced by CategoryPublisher below)
CATEGORY_FILE="$(pwd)/build/category.xml"
cat > "$CATEGORY_FILE" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<site>
   <feature url="features/${FEATURE_ID}_${VERSION}.jar" id="${FEATURE_ID}" version="${VERSION}">
      <category name="claude"/>
   </feature>
   <category-def name="claude" label="Claude AI (Independent Build)">
      <description>Independent third-party Eclipse plug-in that wraps the Claude Code CLI. Not produced by, affiliated with or endorsed by Anthropic. "Claude" and "Claude Code" are trademarks of Anthropic, PBC.</description>
   </category-def>
</site>
EOF

# Locate Eclipse install + launcher (search common paths)
ECLIPSE_HOME=""
for candidate in "C:/eclipse" "C:/eclipse2024-12/eclipse" "C:/eclipse2024-06/eclipse" "C:/dev/eclipse2024-12/eclipse"; do
    if [ -d "$candidate/plugins" ]; then
        ECLIPSE_HOME="$candidate"
        break
    fi
done
if [ -z "$ECLIPSE_HOME" ]; then
    echo "ERROR: Could not find Eclipse install (looked in C:/eclipse2025-12, C:/eclipse, C:/dev/eclipse25-12)" >&2
    exit 1
fi
LAUNCHER_JAR=$(ls "$ECLIPSE_HOME"/plugins/org.eclipse.equinox.launcher_*.jar 2>/dev/null | head -1)
if [ -z "$LAUNCHER_JAR" ]; then
    echo "ERROR: equinox.launcher jar not found in $ECLIPSE_HOME/plugins" >&2
    exit 1
fi
echo "  Using Eclipse: $ECLIPSE_HOME"

# Convert path to file:/C:/... URL (Windows). pwd in git-bash gives /c/dev/... ; need C:/dev/...
to_url() {
    local p="$1"
    # /c/dev/... -> C:/dev/...
    p="$(echo "$p" | sed -E 's|^/([a-zA-Z])/|\1:/|; s|\\|/|g')"
    echo "file:/$p"
}
SITE_URL="$(to_url "$SITE_ABS")"
CATEGORY_URL="$(to_url "$CATEGORY_FILE")"
echo "  Site URL:     $SITE_URL"
echo "  Category URL: $CATEGORY_URL"

# Run FeaturesAndBundlesPublisher: generates content.xml + artifacts.xml from the JARs
java -jar "$LAUNCHER_JAR" \
    -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher \
    -metadataRepository "$SITE_URL" \
    -artifactRepository "$SITE_URL" \
    -metadataRepositoryName "Claude AI Eclipse Plugin Update Site" \
    -artifactRepositoryName "Claude AI Eclipse Plugin Update Site" \
    -source "$SITE_ABS" \
    -compress -publishArtifacts \
    -nosplash 2>&1 | tail -20

# Run CategoryPublisher: adds the Claude AI category referencing the feature
java -jar "$LAUNCHER_JAR" \
    -application org.eclipse.equinox.p2.publisher.CategoryPublisher \
    -metadataRepository "$SITE_URL" \
    -categoryDefinition "$CATEGORY_URL" \
    -compress \
    -nosplash 2>&1 | tail -10

# p2.index helps p2 discover repo type
cat > "$SITE_DIR/p2.index" <<EOF
version = 1
metadata.repository.factory.order = content.xml.xz,content.xml,!
artifact.repository.factory.order = artifacts.xml.xz,artifacts.xml,!
EOF

# Package zip
ZIP_NAME="claude-eclipse-plugin-update-site-${ECLIPSE_TARGET}-${TIMESTAMP}.zip"
rm -f "$ZIP_NAME"
(cd "$SITE_DIR" && jar cMf "../../$ZIP_NAME" .)

echo ""
echo "=========================================="
echo "  Build complete: version ${VERSION}"
echo "=========================================="
echo "  Plugin JAR:   ${PLUGIN_JAR}"
echo "  Feature JAR:  ${FEATURE_JAR}"
echo "  Update site:  ${SITE_DIR}/"
echo "  Zip archive:  ${ZIP_NAME}"
echo ""
echo "To install in Eclipse:"
echo "  1. Help -> Install New Software..."
echo "  2. Click 'Add...' -> 'Archive...' -> select ${ZIP_NAME}"
echo "  3. Check 'Claude AI Eclipse Plugin' and Next/Finish"
