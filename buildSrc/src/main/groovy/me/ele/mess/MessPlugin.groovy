package me.ele.mess

import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.api.BaseVariantOutput
import org.gradle.api.Plugin
import org.gradle.api.Project

class MessPlugin implements Plugin<Project> {

    static final String TAG = "MessPlugin"
    @Override
    void apply(Project project) {
        MessExtension ext = project.extensions.create("mess", MessExtension.class)

        project.afterEvaluate {
            project.plugins.withId('com.android.application') {
                project.android.applicationVariants.all { ApplicationVariant variant ->

                    variant.outputs.each { BaseVariantOutput output ->

                        Util.LOG_PATH = "${project.buildDir.absolutePath}/outputs/logs/${Util.LOG_FINE_NAME}"
                        File messProguardFile = new File(Util.LOG_PATH)
                        if (messProguardFile.exists()) {
                            messProguardFile.delete()
                        }
                        String taskName = "transformClassesAndResourcesWithProguardFor${variant.name.capitalize()}"
                        def proguardTask = project.tasks.findByName(taskName)
                        if (!proguardTask) {
                            return
                        }
                        proguardTask.outputs.upToDateWhen {false}

                        def shrinkResForTask = project.tasks.findByName("transformClassesWithShrinkResFor${variant.name.capitalize()}")
                        boolean hasProguardExecuted = false

                        boolean hasProcessResourcesExecuted = false
                        output.processResources.doLast {
                            if (hasProcessResourcesExecuted) {
                                return
                            }
                            hasProcessResourcesExecuted = true

                            def rulesPath = "${project.buildDir.absolutePath}/intermediates/proguard-rules/${variant.dirName}/aapt_rules.txt"
                            def rulesPathCopy = "${project.buildDir.absolutePath}/intermediates/proguard-rules/${variant.dirName}/aapt_rules_copy.txt"
                            File aaptRules = new File(rulesPath)
                            File aaptRulesCopy = new File(rulesPathCopy)
                            aaptRules.renameTo(aaptRulesCopy)
                            aaptRules.createNewFile()
                            aaptRules << "\n"
//                            Util.log TAG, "new file text = " + aaptRules.text

                            // adjust aaptRules
                            for (String line : aaptRulesCopy.readLines()) {
//                                Util.log TAG, "line: " + line
                                if (line.startsWith("-keep")) {
                                    // -keep class ; len = 12
                                    String tmpLine = line.substring(12, line.length())
                                    for (String white : ext.whiteList) {
                                        if (tmpLine.startsWith(white)) {
                                            Util.log TAG, "add keep class " + line
                                            aaptRules.append(line + "\n")
                                            break
                                        }
                                    }
                                }
                            }
                            Util.log TAG, ""
                            Util.log TAG, "keep aaptRules text = " + aaptRules.text
                            Util.log TAG, ""
                        }

                        proguardTask.doFirst {
                            Util.log TAG, "start ignore proguard components"
                            ext.ignoreProguardComponents.each { String component ->
                                Util.hideProguardTxt(project, component)
                            }
                        }

                        proguardTask.doLast {
                            hasProguardExecuted = true
                            Util.log TAG, "proguard finish, ready to execute rewrite"
                            RewriteComponentTask rewriteTask = project.tasks.create(name: "rewriteComponentFor${variant.name.capitalize()}",
                                    type: RewriteComponentTask
                            ) {
                                applicationVariant = variant
                                whiteList = ext.whiteList
                                variantOutput = output
                            }
                            rewriteTask.execute()
                        }

                        proguardTask.doLast {
                            ext.ignoreProguardComponents.each { String component ->
                                Util.recoverProguardTxt(project, component)
                            }
                        }

                        if (shrinkResForTask) {
                            shrinkResForTask.doFirst {
                                if (hasProguardExecuted) {
                                    return
                                }
                                Util.log TAG, "shrinkResForTask start, ready to execute rewrite"
                                RewriteComponentTask rewriteTask = project.tasks.create(name: "rewriteComponentFor${variant.name.capitalize()}",
                                        type: RewriteComponentTask
                                ) {
                                    applicationVariant = variant
                                    variantOutput = output
                                }
                                rewriteTask.execute()
                            }
                        }

                    }
                }
            }
        }
    }
}
