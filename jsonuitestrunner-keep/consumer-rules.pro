# What a JsonUI UI test takes from the app it runs in. For the build type the tests run on, never the one that ships.
#
# The test APK leaves out every library the app has on its classpath, and R8 removes from the app what the app itself
# does not call. These are whole because the runner's own libraries call into them with members the app does not use.
-keep class androidx.tracing.Trace { *; }
-keep class androidx.concurrent.futures.CallbackToFutureAdapter { *; }
-keep class androidx.concurrent.futures.SuspendToFutureAdapter { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep class kotlinx.coroutines.** { *; }

# What the driver reads from the app's semantics tree (HiddenNodes, StateBridge, ZeroSizeNodes), member by member so
# R8 goes on processing the rest of Compose as it does in the build that ships. KeepRulesTest holds this to the sources.
-keep interface androidx.compose.ui.node.RootForTest { androidx.compose.ui.semantics.SemanticsOwner getSemanticsOwner(); }
-keep class androidx.compose.ui.semantics.SemanticsOwner { androidx.compose.ui.semantics.SemanticsNode getUnmergedRootSemanticsNode(); }
-keep class androidx.compose.ui.semantics.SemanticsNode {
    androidx.compose.ui.semantics.SemanticsConfiguration getConfig();
    java.util.List getChildren();
    long getSize-*();
}
-keep class androidx.compose.ui.semantics.SemanticsConfiguration {
    boolean contains(androidx.compose.ui.semantics.SemanticsPropertyKey);
    java.lang.Object getOrElseNullable(androidx.compose.ui.semantics.SemanticsPropertyKey, kotlin.jvm.functions.Function0);
}
-keep class androidx.compose.ui.semantics.SemanticsProperties {
    public static final androidx.compose.ui.semantics.SemanticsProperties INSTANCE;
    androidx.compose.ui.semantics.SemanticsPropertyKey getDisabled();
    androidx.compose.ui.semantics.SemanticsPropertyKey getEditableText();
    androidx.compose.ui.semantics.SemanticsPropertyKey getTestTag();
    androidx.compose.ui.semantics.SemanticsPropertyKey getText();
}
-keep class androidx.compose.ui.semantics.SemanticsPropertiesAndroid {
    public static final androidx.compose.ui.semantics.SemanticsPropertiesAndroid INSTANCE;
    androidx.compose.ui.semantics.SemanticsPropertyKey getTestTagsAsResourceId();
}
-keep class androidx.compose.ui.semantics.SemanticsPropertyKey { java.lang.String getName(); }
-keep class androidx.compose.ui.text.AnnotatedString { java.lang.String getText(); }
-keep class androidx.compose.ui.unit.IntSize {
    public static int getWidth-impl(long);
    public static int getHeight-impl(long);
}
