#!/bin/bash
# Build Eclipse plugin JAR + P2 update site for installation via Help -> Install New Software
set -e

cd "$(dirname "$0")"
PROJECT_DIR="$(pwd)"

# ==================== Configuration ====================
TIMESTAMP="${1:-$(date +%Y%m%d%H%M)}"
VERSION="1.0.0.${TIMESTAMP}"
ECLIPSE_TARGET="4.38"
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
           "$ECLIPSE_PLUGINS_DIR"/org.eclipse.jdt.core_*.jar; do
    [ -f "$jar" ] && CP="$CP;$jar"
done

rm -rf bin && mkdir -p bin
javac -source 11 -target 11 -cp "$CP" -d bin -sourcepath src $(find src -name "*.java") 2>&1 | tail -5

# ==================== Step 2: Update MANIFEST.MF version ====================
echo "[2/5] Updating manifest version..."
sed -i "s/^Bundle-Version: .*/Bundle-Version: ${VERSION}/" META-INF/MANIFEST.MF

# ==================== Step 3: Build plugin JAR ====================
echo "[3/5] Packaging plugin JAR..."
PLUGIN_JAR="build/${PLUGIN_ID}_${VERSION}.jar"
mkdir -p build
rm -f "$PLUGIN_JAR"
jar cfm "$PLUGIN_JAR" META-INF/MANIFEST.MF -C bin . -C . plugin.xml icons
echo "  -> $PLUGIN_JAR ($(stat -c%s "$PLUGIN_JAR") bytes)"

# ==================== Step 4: Build Feature JAR ====================
echo "[4/5] Building feature JAR..."
FEATURE_WORK="build/feature-work"
rm -rf "$FEATURE_WORK" && mkdir -p "$FEATURE_WORK/META-INF"

cat > "$FEATURE_WORK/feature.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<feature id="${FEATURE_ID}" label="Claude AI Eclipse Plugin"
         version="${VERSION}" provider-name="Anthropic (Unofficial)">
   <description url="https://github.com/anthropics/claude-code">Unofficial Claude AI plugin for Eclipse IDE.</description>
   <copyright>Copyright (c) 2024 Anthropic (Unofficial). All rights reserved.</copyright>
   <license>This plugin is provided as-is for personal use.</license>
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

# ==================== Step 5: Build P2 Update Site ====================
echo "[5/5] Building P2 update site..."
SITE_DIR="build/update-site"
rm -rf "$SITE_DIR"
mkdir -p "$SITE_DIR/plugins" "$SITE_DIR/features"
cp "$PLUGIN_JAR" "$SITE_DIR/plugins/"
cp "$FEATURE_JAR" "$SITE_DIR/features/"

# Compute checksums and sizes
PLUGIN_SIZE=$(stat -c%s "$PLUGIN_JAR")
PLUGIN_SHA256=$(sha256sum "$PLUGIN_JAR" | cut -d' ' -f1)
PLUGIN_SHA512=$(sha512sum "$PLUGIN_JAR" | cut -d' ' -f1)
FEATURE_SIZE=$(stat -c%s "$FEATURE_JAR")
FEATURE_SHA256=$(sha256sum "$FEATURE_JAR" | cut -d' ' -f1)
FEATURE_SHA512=$(sha512sum "$FEATURE_JAR" | cut -d' ' -f1)
P2_TIMESTAMP=$(date +%s)000

# p2.index
cat > "$SITE_DIR/p2.index" <<EOF
version = 1
metadata.repository.factory.order = content.xml,!
artifact.repository.factory.order = artifacts.xml,!
EOF

# content.xml (metadata repository)
cat > "$SITE_DIR/content.xml" <<EOF
<?xml version='1.0' encoding='UTF-8'?>
<?metadataRepository version='1.2.0'?>
<repository name='Claude AI Eclipse Plugin Update Site' type='org.eclipse.equinox.internal.p2.metadata.repository.LocalMetadataRepository' version='1.0.0'>
  <properties size='2'>
    <property name='p2.timestamp' value='${P2_TIMESTAMP}'/>
    <property name='p2.compressed' value='false'/>
  </properties>
  <units size='4'>
    <unit id='${FEATURE_ID}.feature.group' version='${VERSION}' singleton='false'>
      <update id='${FEATURE_ID}.feature.group' range='[0.0.0,${VERSION})' severity='0'/>
      <properties size='5'>
        <property name='org.eclipse.equinox.p2.name' value='Claude AI Eclipse Plugin'/>
        <property name='org.eclipse.equinox.p2.description' value='Unofficial Claude AI plugin for Eclipse IDE.'/>
        <property name='org.eclipse.equinox.p2.description.url' value='https://github.com/anthropics/claude-code'/>
        <property name='org.eclipse.equinox.p2.provider' value='Anthropic (Unofficial)'/>
        <property name='org.eclipse.equinox.p2.type.group' value='true'/>
      </properties>
      <provides size='1'>
        <provided namespace='org.eclipse.equinox.p2.iu' name='${FEATURE_ID}.feature.group' version='${VERSION}'/>
      </provides>
      <requires size='2'>
        <required namespace='org.eclipse.equinox.p2.iu' name='${PLUGIN_ID}' range='[${VERSION},${VERSION}]'/>
        <required namespace='org.eclipse.equinox.p2.iu' name='${FEATURE_ID}.feature.jar' range='[${VERSION},${VERSION}]'>
          <filter>
            (org.eclipse.update.install.features=true)
          </filter>
        </required>
      </requires>
      <licenses size='1'>
        <license>
          This plugin is provided as-is for personal use.
        </license>
      </licenses>
      <copyright>
        Copyright (c) 2024 Anthropic (Unofficial). All rights reserved.
      </copyright>
    </unit>
    <unit id='${FEATURE_ID}.feature.jar' version='${VERSION}'>
      <properties size='4'>
        <property name='org.eclipse.equinox.p2.name' value='Claude AI Eclipse Plugin'/>
        <property name='org.eclipse.equinox.p2.description' value='Unofficial Claude AI plugin for Eclipse IDE.'/>
        <property name='org.eclipse.equinox.p2.description.url' value='https://github.com/anthropics/claude-code'/>
        <property name='org.eclipse.equinox.p2.provider' value='Anthropic (Unofficial)'/>
      </properties>
      <provides size='3'>
        <provided namespace='org.eclipse.equinox.p2.iu' name='${FEATURE_ID}.feature.jar' version='${VERSION}'/>
        <provided namespace='org.eclipse.equinox.p2.eclipse.type' name='feature' version='1.0.0'/>
        <provided namespace='org.eclipse.update.feature' name='${FEATURE_ID}' version='${VERSION}'/>
      </provides>
      <filter>
        (org.eclipse.update.install.features=true)
      </filter>
      <artifacts size='1'>
        <artifact classifier='org.eclipse.update.feature' id='${FEATURE_ID}' version='${VERSION}'/>
      </artifacts>
      <touchpoint id='org.eclipse.equinox.p2.osgi' version='1.0.0'/>
      <touchpointData size='1'>
        <instructions size='1'>
          <instruction key='zipped'>
            true
          </instruction>
        </instructions>
      </touchpointData>
      <licenses size='1'>
        <license>
          This plugin is provided as-is for personal use.
        </license>
      </licenses>
      <copyright>
        Copyright (c) 2024 Anthropic (Unofficial). All rights reserved.
      </copyright>
    </unit>
    <unit id='${PLUGIN_ID}' version='${VERSION}' generation='2'>
      <update id='${PLUGIN_ID}' range='[0.0.0,${VERSION})' severity='0'/>
      <properties size='2'>
        <property name='org.eclipse.equinox.p2.name' value='Claude AI Plugin'/>
        <property name='org.eclipse.equinox.p2.provider' value='Anthropic (Unofficial)'/>
      </properties>
      <provides size='2'>
        <provided namespace='org.eclipse.equinox.p2.iu' name='${PLUGIN_ID}' version='${VERSION}'/>
        <provided namespace='osgi.bundle' name='${PLUGIN_ID}' version='${VERSION}'/>
      </provides>
      <requires size='9'>
        <required namespace='osgi.bundle' name='org.eclipse.ui' range='0.0.0'/>
        <required namespace='osgi.bundle' name='org.eclipse.core.runtime' range='0.0.0'/>
        <required namespace='osgi.bundle' name='org.eclipse.ui.editors' range='0.0.0'/>
        <required namespace='osgi.bundle' name='org.eclipse.ui.workbench.texteditor' range='0.0.0'/>
        <required namespace='osgi.bundle' name='org.eclipse.jface' range='0.0.0'/>
        <required namespace='osgi.bundle' name='org.eclipse.jface.text' range='0.0.0'/>
        <required namespace='osgi.bundle' name='org.eclipse.core.resources' range='0.0.0'/>
        <required namespace='osgi.bundle' name='org.eclipse.ui.ide' range='0.0.0'/>
        <required namespace='osgi.bundle' name='org.eclipse.equinox.security' range='0.0.0'/>
      </requires>
      <artifacts size='1'>
        <artifact classifier='osgi.bundle' id='${PLUGIN_ID}' version='${VERSION}'/>
      </artifacts>
      <touchpoint id='org.eclipse.equinox.p2.osgi' version='1.0.0'/>
      <touchpointData size='1'>
        <instructions size='1'>
          <instruction key='manifest'>
            Bundle-SymbolicName: ${PLUGIN_ID};singleton:=true&amp;#xA;Bundle-Version: ${VERSION}&amp;#xA;
          </instruction>
        </instructions>
      </touchpointData>
    </unit>
    <unit id='Claude_AI_Eclipse_Plugin' version='${VERSION}' singleton='false'>
      <properties size='2'>
        <property name='org.eclipse.equinox.p2.name' value='Claude AI Eclipse Plugin'/>
        <property name='org.eclipse.equinox.p2.type.category' value='true'/>
      </properties>
      <provides size='1'>
        <provided namespace='org.eclipse.equinox.p2.iu' name='Claude_AI_Eclipse_Plugin' version='${VERSION}'/>
      </provides>
      <requires size='1'>
        <required namespace='org.eclipse.equinox.p2.iu' name='${FEATURE_ID}.feature.group' range='[${VERSION},${VERSION}]'/>
      </requires>
      <touchpoint id='null' version='0.0.0'/>
    </unit>
  </units>
</repository>
EOF

# artifacts.xml (artifact repository)
cat > "$SITE_DIR/artifacts.xml" <<EOF
<?xml version='1.0' encoding='UTF-8'?>
<?artifactRepository version='1.1.0'?>
<repository name='Claude AI Eclipse Plugin Update Site' type='org.eclipse.equinox.p2.artifact.repository.simpleRepository' version='1'>
  <properties size='2'>
    <property name='p2.timestamp' value='${P2_TIMESTAMP}'/>
    <property name='p2.compressed' value='false'/>
  </properties>
  <mappings size='3'>
    <rule filter='(&amp; (classifier=osgi.bundle))' output='\${repoUrl}/plugins/\${id}_\${version}.jar'/>
    <rule filter='(&amp; (classifier=binary))' output='\${repoUrl}/binary/\${id}_\${version}'/>
    <rule filter='(&amp; (classifier=org.eclipse.update.feature))' output='\${repoUrl}/features/\${id}_\${version}.jar'/>
  </mappings>
  <artifacts size='2'>
    <artifact classifier='osgi.bundle' id='${PLUGIN_ID}' version='${VERSION}'>
      <properties size='4'>
        <property name='artifact.size' value='${PLUGIN_SIZE}'/>
        <property name='download.size' value='${PLUGIN_SIZE}'/>
        <property name='download.checksum.sha-512' value='${PLUGIN_SHA512}'/>
        <property name='download.checksum.sha-256' value='${PLUGIN_SHA256}'/>
      </properties>
    </artifact>
    <artifact classifier='org.eclipse.update.feature' id='${FEATURE_ID}' version='${VERSION}'>
      <properties size='5'>
        <property name='artifact.size' value='${FEATURE_SIZE}'/>
        <property name='download.size' value='${FEATURE_SIZE}'/>
        <property name='download.checksum.sha-512' value='${FEATURE_SHA512}'/>
        <property name='download.checksum.sha-256' value='${FEATURE_SHA256}'/>
        <property name='download.contentType' value='application/zip'/>
      </properties>
    </artifact>
  </artifacts>
</repository>
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
