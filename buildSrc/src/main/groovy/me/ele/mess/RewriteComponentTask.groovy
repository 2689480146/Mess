package me.ele.mess

import com.android.build.gradle.AppExtension
import com.android.build.gradle.api.ApkVariant
import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.api.BaseVariantOutput
import com.android.build.gradle.tasks.ProcessAndroidResources
import com.android.builder.core.AndroidBuilder
import com.android.sdklib.BuildToolInfo
import groovy.io.FileType
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import proguard.obfuscate.MappingProcessor
import proguard.obfuscate.MappingReader

import java.lang.reflect.Field
import java.nio.charset.StandardCharsets

class RewriteComponentTask extends DefaultTask {

    static final String CHARSET = StandardCharsets.UTF_8.name()
    static final String TAG = "RewriteComponentTask"

    @Input
    ApplicationVariant applicationVariant

    @Input
    Iterable<String> whiteList

    @Input
    BaseVariantOutput variantOutput

    @TaskAction
    void rewrite() {
        Util.log TAG, "start rewrite task"

        Map<String, String> map = new LinkedHashMap<>();
        MappingReader reader = new MappingReader(applicationVariant.mappingFile)
        reader.pump(new MappingProcessor() {
            @Override
            boolean processClassMapping(String className, String newClassName) {
                map.put(className, newClassName)
                return false
            }

            @Override
            void processFieldMapping(String className, String fieldType, String fieldName, String newClassName, String newFieldName) {

            }

            @Override
            void processMethodMapping(String className, int firstLineNumber, int lastLineNumber, String methodReturnType, String methodName, String methodArguments, String newClassName, int newFirstLineNumber, int newLastLineNumber, String newMethodName) {

            }
        })

        // sort by key length in case of following scenario:
        // key1: me.ele.foo -> me.ele.a
        // key2: me.ele.fooNew -> me.ele.b
        // if we do not sort by length from long to short,
        // the key2 will be mapped to, me.ele.aNew
        map = Util.sortMapping(map)

        // parse aapt_rules
        def rulesPathCopy = "${project.buildDir.absolutePath}/intermediates/proguard-rules/${applicationVariant.dirName}/aapt_rules_copy.txt"
        Map<String, Map<String, String>> replaceMap = Util.parseAaptRules(rulesPathCopy, map, whiteList)

        Util.log TAG, ""
        // AndroidManifest.xml
        if (replaceMap.containsKey("AndroidManifest.xml")) {
            Util.log TAG, "gradle version: " + project.getGradle().getGradleVersion()
            int gradleVersion = Integer.parseInt(project.getGradle().getGradleVersion().split("\\.")[0])
            String realPath = ""
            if (gradleVersion <= 3) {
                realPath = "${project.buildDir.absolutePath}/intermediates/manifests/full/${getSubResPath()}/AndroidManifest.xml"
            } else {
                Util.log TAG, "flavorName: " + applicationVariant.flavorName
                Util.log TAG, "assembleName: " + applicationVariant.assemble.name
                Util.log TAG, "getDirName: " + applicationVariant.getDirName()

                String adjustAssembleName = applicationVariant.assemble.name.substring(8) // assembleDebug
                String midPath = "process${adjustAssembleName}Manifest"
                char[] chars = adjustAssembleName.toCharArray()
                chars[0] = Character.toLowerCase(chars[0])
                adjustAssembleName = chars.toString()
                realPath = "${project.buildDir.absolutePath}/intermediates/merged_manifests/${adjustAssembleName}/${midPath}/merged/AndroidManifest.xml"
            }
            Util.log TAG, "rewrite ${realPath}"
            replaceMap.get("AndroidManifest.xml").each { k, v ->
                Util.log TAG, "replace ${k} -> ${v}"
                writeLine(realPath, k, v)
            }
            Util.log TAG, ''
        }


//        replaceMap.each { k, v ->
//            Util.log TAG, "rewrite key = " + k
//            ((Map) v).each { k2, v2 ->
//                Util.log TAG, "rewrite key2 = " + k2
//                Util.log TAG, "rewrite value2 = " + v2
//            }
//        }


        Util.log TAG, ""
        long t0 = System.currentTimeMillis()
        File resDir = new File(getResPath())
        for (File dir : resDir.listFiles()) {
            if (dir.isFile() && dir.name.endsWith(".xml.flat")) {
                List<String> xmlPath = Util.parseAaptRulesGetXml(rulesPathCopy)
                String resPath = getResPath()
                BuildToolInfo buildToolInfo = applicationVariant.androidBuilder.getTargetInfo().getBuildTools()
                String aapt2Path = buildToolInfo.getPath(BuildToolInfo.PathId.AAPT2)
                Util.log TAG, "####### aapt2Path path = " + aapt2Path + " ###########"
                for (String path : xmlPath) {
                    String[] keyStrs = path.split("/")
                    int len = keyStrs.length
                    if (len >= 2) {
                        String key = keyStrs[len - 2] + "/" + keyStrs[len - 1]
                        if (replaceMap.containsKey(key)) {
                            File file = new File(path)
                            String orgTxt = file.getText(CHARSET)
                            String newTxt = orgTxt
                            Map<String, String> mp = replaceMap.get(key)
                            Util.log TAG, 'rewrite file: ' + file.absolutePath
                            mp.each { k, v ->
                                boolean hasContains = newTxt.contains(k+"\n") || newTxt.contains(k+"\r") || newTxt.contains(k+"\r\n") || newTxt.contains(k+" ") || newTxt.contains(k+">")
                                newTxt = newTxt.replace(k+"\n", v+"\n")
                                newTxt = newTxt.replace(k+"\r", v+"\r")
                                newTxt = newTxt.replace(k+"\r\n", v+"\r\n")
                                newTxt = newTxt.replace(k+" ", v+" ")
                                newTxt = newTxt.replace(k+">", v+">")
                                Util.log TAG, "replace ${k} -> ${v}, sucessed: ${hasContains}"
                                if (!hasContains) {
                                    Util.log TAG, "Error: replace ${k} -> ${v} failed."
                                }
                            }
                            if (newTxt != orgTxt) {
                                file.setText(newTxt, CHARSET)
                                // aapt compile
                                def sout = new StringBuilder(), serr = new StringBuilder()
                                def proc = "${aapt2Path} compile -o ${resPath} ${path}".execute()
                                proc.consumeProcessOutput(sout, serr)
                                proc.waitForOrKill(1000)
                                Util.log TAG, "rewrite out> $sout err> $serr"

                                // recover xml file
                                file.setText(orgTxt, CHARSET)
                            }
                            Util.log TAG, ""
                        }
                    }
                }
                break
            }
            if (dir.exists() && dir.isDirectory() && isLayoutsDir(dir.name)) {
                dir.eachFileRecurse(FileType.FILES) { File file ->
                    String[] paths = file.absolutePath.split('/')
                    int len = paths.length
                    String key = paths[len - 2] + "/" + paths[len - 1]
                    if (replaceMap.containsKey(key)) {
                        String orgTxt = file.getText(CHARSET)
                        String newTxt = orgTxt
                        Map<String, String> mp = replaceMap.get(key)
                        Util.log TAG, 'rewrite file: ' + file.absolutePath
                        mp.each { k, v ->
                            newTxt = newTxt.replace(k+"\n", v+"\n")
                            newTxt = newTxt.replace(k+" ", v+" ")
                            newTxt = newTxt.replace(k+">", v+">")
                            Util.log TAG, "replace ${k} -> ${v}"
                        }
                        if (newTxt != orgTxt) {
//                            Util.log TAG, 'rewrite file: ' + file.absolutePath
                            file.setText(newTxt, CHARSET)
                        }
                        Util.log TAG, ""
                    }
                }
            }
        }

        Util.log TAG, 'write layout and menu xml spend: ' + ((System.currentTimeMillis() - t0) / 1000) + ' s'


        ProcessAndroidResources processAndroidResourcesTask = variantOutput.processResources
        try {
            //this is for Android gradle 2.3.3 & above
            Field outcomeField = processAndroidResourcesTask.state.getClass().getDeclaredField("outcome")
            outcomeField.setAccessible(true)
            outcomeField.set(processAndroidResourcesTask.state, null)
        } catch (Throwable e) {
            processAndroidResourcesTask.state.executed = false
        }
        processAndroidResourcesTask.execute()

        Util.log TAG, "execute process resources again"

        Util.log TAG, "end rewrite task"
    }

    void writeLine(String path, String oldStr, String newStr) {
        File f = new File(path)

        StringBuilder builder = new StringBuilder()
        f.eachLine { line ->
            //<me.ele.base.widget.LoadingViewPager -> <me.ele.aaa
            // app:actionProviderClass="me.ele.base.ui.SearchViewProvider" -> app:actionProviderClass="me.ele.bbv"
            if (line.contains("<${oldStr}") || line.contains("${oldStr}>") || line.contains("${oldStr}\"")) {
                oldStr = URLEncoder.encode(oldStr, CHARSET)
                newStr = URLEncoder.encode(newStr, CHARSET)
                line = URLEncoder.encode(line, CHARSET)
                line = URLDecoder.decode(line.replaceAll(oldStr, newStr), CHARSET)
            }
            builder.append(line)
            builder.append("\n")
        }

        f.delete()
        f.withWriter(CHARSET) { writer ->
            writer.write(builder.toString())
        }
    }

    String getResPath() {
        String resPath = "${project.buildDir.absolutePath}/intermediates/res/merged/${getSubResPath()}"
        if (project.android.dataBinding.enabled) {

            project.rootProject.buildscript.configurations.classpath.resolvedConfiguration.firstLevelModuleDependencies.each {
                if (it.moduleGroup == "com.android.tools.build" && it.moduleName == "gradle") {
                    if (it.moduleVersion.startsWith("1") || it.moduleVersion.startsWith("2")) {
                        resPath = "${project.buildDir.absolutePath}/intermediates/data-binding-layout-out/${getSubResPath()}"
                        return
                    }
                }
            }

        }
        return resPath
    }

    boolean isLayoutsDir(String name) {
        // layout and menu xml
        // sometimes, we can use a string res for value, e.g app:behavior="@string/my_behavior"
        // <string name="my_behavior">me.ele.mess.MyBehavior</string>
        if (name.startsWith("layout") || name.startsWith("menu") || name.startsWith("values")) {
            return true
        }
        return false
    }

    String getSubResPath() {
        return applicationVariant.getDirName()
    }
}
