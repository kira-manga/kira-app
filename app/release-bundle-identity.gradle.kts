import com.android.aapt.Resources
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

// Uses aapt2-proto already supplied by AGP: no downloaded inspector or release toolchain change.
val storePackage = "me.manga.kira"
val androidNamespace = "http://schemas.android.com/apk/res/android"
val debuggableResourceId = 0x0101000f
val manifestEntry = "base/manifest/AndroidManifest.xml"

fun requireStoreIdentity(condition: Boolean, reason: String) {
    if (!condition) throw GradleException("Store AAB identity refused: $reason")
}

fun verifyStoreBundle(bundle: File) {
    val node = ZipFile(bundle).use { zip ->
        val entries = zip.entries().asSequence().filter { it.name == manifestEntry }.take(2).toList()
        requireStoreIdentity(entries.size == 1, "expected one base binary manifest")
        val bytes = zip.getInputStream(entries.single()).use { it.readNBytes(1_048_577) }
        requireStoreIdentity(bytes.size <= 1_048_576, "manifest exceeds inspection bound")
        Resources.XmlNode.parseFrom(bytes)
    }
    requireStoreIdentity(node.hasElement() && node.element.name == "manifest" && node.element.namespaceUri.isEmpty(), "invalid manifest root")
    val packages = node.element.attributeList.filter { it.name == "package" && it.namespaceUri.isEmpty() }
    requireStoreIdentity(packages.size == 1 && packages.single().value == storePackage, "non-canonical package")
    val packageAttribute = packages.single()
    requireStoreIdentity(packageAttribute.resourceId == 0, "invalid package resource")
    if (packageAttribute.hasCompiledItem()) {
        val item = packageAttribute.compiledItem
        requireStoreIdentity(item.hasStr() && item.str.value == storePackage, "conflicting compiled package")
    }
    val applications = node.element.childList.filter { it.hasElement() && it.element.name == "application" && it.element.namespaceUri.isEmpty() }
    requireStoreIdentity(applications.size == 1, "expected one application")
    val debugAttributes = applications.single().element.attributeList.filter {
        it.resourceId == debuggableResourceId || (it.name == "debuggable" && it.namespaceUri == androidNamespace)
    }
    requireStoreIdentity(debugAttributes.size <= 1, "ambiguous debuggable attribute")
    debugAttributes.singleOrNull()?.let { attribute ->
        requireStoreIdentity(attribute.name == "debuggable" && attribute.namespaceUri == androidNamespace, "invalid debuggable identity")
        requireStoreIdentity(attribute.resourceId == 0 || attribute.resourceId == debuggableResourceId, "invalid debuggable resource")
        requireStoreIdentity(attribute.value.isEmpty() || attribute.value == "false", "debuggable app")
        if (attribute.hasCompiledItem()) {
            val item = attribute.compiledItem
            requireStoreIdentity(item.hasPrim() && item.prim.hasBooleanValue() && !item.prim.booleanValue, "debuggable or unresolved app")
        } else {
            requireStoreIdentity(attribute.value == "false", "unresolved debuggable app")
        }
    }
    // Absence is Android's non-debuggable default. A compiled true cannot be hidden by raw "false".
}

extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
    val expected = if (variant.buildType == "debug") "$storePackage.debug" else storePackage
    requireStoreIdentity(variant.applicationId.get() == expected, "unexpected effective variant application ID")
    if (variant.buildType == "release") {
        val suppliedBundle = providers.gradleProperty("kira.releaseBundleToVerify")
        val bundle = if (suppliedBundle.isPresent) {
            layout.file(suppliedBundle.map { rootProject.file(it) })
        } else {
            variant.artifacts.get(SingleArtifact.BUNDLE)
        }
        tasks.register("verifyReleaseBundleIdentity") {
            group = "verification"
            description = "Reject development identities/debuggable apps in the exact AAB selected for upload"
            inputs.file(bundle)
            doLast {
                verifyStoreBundle(bundle.get().asFile)
                logger.lifecycle("Store AAB canonical package and non-debuggable binary manifest verified")
            }
        }
    }
}

tasks.register("testReleaseBundleIdentity") {
    group = "verification"
    description = "Exercise the actual AAB inspector with binary protobuf identity fixtures (not signed artifacts)"
    doLast {
        fun manifest(packageName: String, debug: Resources.XmlAttribute? = null): Resources.XmlNode {
            val application = Resources.XmlElement.newBuilder().setName("application")
            if (debug != null) application.addAttribute(debug)
            return Resources.XmlNode.newBuilder().setElement(
                Resources.XmlElement.newBuilder().setName("manifest")
                    .addAttribute(Resources.XmlAttribute.newBuilder().setName("package").setValue(packageName))
                    .addChild(Resources.XmlNode.newBuilder().setElement(application)),
            ).build()
        }
        fun debugAttribute(value: Boolean): Resources.XmlAttribute = Resources.XmlAttribute.newBuilder()
            .setName("debuggable").setNamespaceUri(androidNamespace).setResourceId(debuggableResourceId)
            .setCompiledItem(Resources.Item.newBuilder().setPrim(Resources.Primitive.newBuilder().setBooleanValue(value)))
            .build()
        val fixture = File(temporaryDir, "identity-fixture.aab")
        fun inspect(node: Resources.XmlNode, allowed: Boolean) {
            ZipOutputStream(fixture.outputStream()).use {
                it.putNextEntry(ZipEntry(manifestEntry))
                it.write(node.toByteArray())
                it.closeEntry()
            }
            val refusal = runCatching { verifyStoreBundle(fixture) }.exceptionOrNull()
            check(if (allowed) refusal == null else refusal is GradleException) { "Binary AAB identity fixture had unexpected outcome" }
        }
        try {
            inspect(manifest(storePackage), true)
            inspect(manifest(storePackage, debugAttribute(false)), true)
            inspect(manifest("$storePackage.debug", debugAttribute(true)), false)
            inspect(manifest("$storePackage.debug", debugAttribute(false)), false)
            inspect(manifest(storePackage, debugAttribute(true)), false)
            inspect(manifest(storePackage, debugAttribute(true).toBuilder().setValue("false").build()), false)
            inspect(manifest(storePackage, debugAttribute(true).toBuilder().setName("misnamed").build()), false)
            inspect(manifest(storePackage, debugAttribute(false).toBuilder().clearCompiledItem().setValue("@bool/debuggable").build()), false)
            val hiddenPackage = manifest(storePackage).toBuilder()
            hiddenPackage.elementBuilder.getAttributeBuilder(0).setCompiledItem(
                Resources.Item.newBuilder().setStr(Resources.String.newBuilder().setValue("$storePackage.debug")),
            )
            inspect(hiddenPackage.build(), false)
            logger.lifecycle("9 binary AAB identity fixtures verified; no signing or Store delivery claimed")
        } finally {
            fixture.delete()
        }
    }
}
