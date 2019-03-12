package me.ele.mess

import com.android.build.gradle.api.ApkVariant
import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.api.BaseVariantOutput
import com.android.sdklib.build.ApkBuilder
import org.gradle.api.Plugin
import org.gradle.api.Project

class MessPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        MessExtension ext = project.extensions.create("mess", MessExtension.class)

        project.afterEvaluate {
            project.plugins.withId('com.android.application') {
                project.android.applicationVariants.all { ApplicationVariant variant ->

                    variant.outputs.each { BaseVariantOutput output ->

                        String taskName = "transformClassesAndResourcesWithProguardFor${variant.name.capitalize()}"
                        def proguardTask = project.tasks.findByName(taskName)
                        if (!proguardTask) {
                            return
                        }

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
                            println "MessTag new file text = " + aaptRules.text

                            // adjust aaptRules
                            List<String> whiteList = Util.parseWhiteList("${project.rootDir}/activityProguard/whiteList")

                            for (String line : aaptRulesCopy.readLines()) {
//                                println "MessTag: line: " + line
                                if (line.startsWith("-keep")) {
                                    // -keep class ; len = 12
                                    String tmpLine = line.substring(12, line.length())
                                    for (String white : whiteList) {
                                        if (tmpLine.startsWith(white)) {
                                            println "MessTag: add keep class " + line
                                            aaptRules.append(line + "\n")
                                            break
                                        }
                                    }
                                }
                            }
                            println "MessTag aaptRules text = " + aaptRules.text
                        }

                        proguardTask.doFirst {
                            println "start ignore proguard components"
                            ext.ignoreProguardComponents.each { String component ->
                                Util.hideProguardTxt(project, component)
                            }
                        }

                        proguardTask.doLast {
                            println "proguard finish, ready to execute rewrite"
                            RewriteComponentTask rewriteTask = project.tasks.create(name: "rewriteComponentFor${variant.name.capitalize()}",
                                    type: RewriteComponentTask
                            ) {
                                applicationVariant = variant
                                variantOutput = output
                            }
                            rewriteTask.execute()
                        }

                        proguardTask.doLast {
                            ext.ignoreProguardComponents.each { String component ->
                                Util.recoverProguardTxt(project, component)
                            }
                        }
                    }
                }
            }
        }
    }
}
