package me.ele.mess

import com.android.builder.model.MavenCoordinates
import com.google.common.io.Files
import groovy.io.FileType
import org.gradle.api.Project

public class Util {

  public static MavenCoordinates parseMavenString(String component) {
    String[] arrays = component.split(":")
    return new MavenCoordinates() {
      @Override
      String getGroupId() {
        return arrays[0]
      }

      @Override
      String getArtifactId() {
        return arrays[1]
      }

      @Override
      String getVersion() {
        return arrays[2]
      }

      @Override
      String getPackaging() {
        return null
      }

      @Override
      String getClassifier() {
        return null
      }

      String getVersionlessId() {
        return null
      }
    }
  }

  public static void hideProguardTxt(Project project, String component) {
    renameProguardTxt(project, component, 'proguard.txt', 'proguard.txt~')
  }

  public static void recoverProguardTxt(Project project, String component) {
    renameProguardTxt(project, component, 'proguard.txt~', 'proguard.txt')
  }

  private static void renameProguardTxt(Project project, String component, String orgName,
      String newName) {
    MavenCoordinates mavenCoordinates = parseMavenString(component)
    File bundlesDir = new File(project.buildDir, "intermediates/exploded-aar")
    File bundleDir = new File(bundlesDir,
        "${mavenCoordinates.groupId}/${mavenCoordinates.artifactId}")
    if (!bundleDir.exists()) return
    bundleDir.eachFileRecurse(FileType.FILES) { File f ->
      if (f.name == orgName) {
        File targetFile = new File(f.parentFile.absolutePath, newName)
        println "rename file ${f.absolutePath} to ${targetFile.absolutePath}"
        Files.move(f, targetFile)
      }
    }
  }

  public static Map<String, String> sortMapping(Map<String, String> map) {
    List<Map.Entry<String, String>> list = new LinkedList<>(map.entrySet());
    Collections.sort(list, new Comparator<Map.Entry<String, String>>() {
      public int compare(Map.Entry<String, String> o1, Map.Entry<String, String> o2) {
        return o2.key.length() - o1.key.length()
      }
    });

    Map<String, String> result = new LinkedHashMap<>();
    for (Iterator<Map.Entry<String, String>> it = list.iterator(); it.hasNext();) {
      Map.Entry<String, String> entry = (Map.Entry<String, String>) it.next();
      result.put(entry.getKey(), entry.getValue());
    }

    return result;
  }
    /**
     * aapt_rule sample:
     * # view res/layout/abc_alert_dialog_button_bar_material.xml #generated:43
     * # view res/layout/abc_alert_dialog_material.xml #generated:52
     * # view res/layout/abc_alert_dialog_material.xml #generated:66
     * # view res/layout/abc_alert_dialog_title_material.xml #generated:56
     * -keep class android.support.v4.widget.Space { <init>(...); }
     *
     * # Referenced at /Users/xxx/MyProject/app-project/app/src/main/res/layout/activity_filter_rec_detail.xml:69
     * # Referenced at /Users/xxx/MyProject/app-project/app/src/main/res/layout/activity_color_filter_rec_detail.xml:67
     * -keep class cn.xxx.PhotoFilterViewIndicator { <init>(...); }
     * @param rulesPath
     * @return
     */
  public static Map<String, Map<String, String>> parseAaptRules(String rulesPath, Map mappingMap) {
      File aaptRules = new File(rulesPath)
      List<String> tmpXmlPath = new LinkedList<String>()
      Map<String, Map<String, String>> resultMap = new HashMap<String, Map<String, String>>()
      for (String line : aaptRules.readLines()) {
          if (line.startsWith("# view")) {
              line = line.replace("# view ", '')
              if (line.split(' ')[0].equals("AndroidManifest.xml")) {
                  tmpXmlPath.add("AndroidManifest.xml")
              } else {
                  String[] pathStr = line.split(' ')[0].split('/')
                  int len = pathStr.length
                  if (len >= 2) {
                      String keyPath = pathStr[len - 2] + "/" + pathStr[len - 1]
                      tmpXmlPath.add(keyPath)
                  }
              }
          } else if (line.startsWith("# Referenced ")) { // aapt2
              line = line.replace("# Referenced at ", '')
              if (line.contains("AndroidManifest.xml")) {
                  tmpXmlPath.add("AndroidManifest.xml")
              } else {
                  String[] pathStr = line.split(":")[0].split('/')
                  int len = pathStr.length
                  if (len >= 2) {
                      String keyPath = pathStr[len - 2] + "/" + pathStr[len - 1]
                      tmpXmlPath.add(keyPath)
                  }
              }
          } else if (line.startsWith("-keep class ")) {
              line = line.replace("-keep class ", '')
              String[] strings = line.split(" ")

              if (strings.length > 1) {
                  String className = strings[0]
//                  println "rewrite className = " + className
                  if (className == null || className.isEmpty()) {
                      continue
                  }
                  String value = mappingMap.get(className)
//                  println "rewrite mappingValue = " + value
                  if (value != null && !value.isEmpty() && !className.equals(value)) {
                      for (String path : tmpXmlPath) {
                          if (resultMap.containsKey(path)) {
                              ((Map) resultMap.get(path)).put(className, value)
                          } else {
                              Map<String, String> subMap = new HashMap<String, String>()
                              subMap.put(className, value)
                              resultMap.put(path, subMap)
                          }
                      }
                  }
                  tmpXmlPath.clear()
              }
          }
      }
      return resultMap
  }
    /**
     * # Referenced at /Users/xxx/MyProject/app-project/app/src/main/res/layout/activity_filter_rec_detail.xml:69
     * # Referenced at /Users/xx/MyProject/app-project/app/src/main/res/layout/activity_color_filter_rec_detail.xml:67
     * -keep class cn.xxx.PhotoFilterViewIndicator { <init>(...); }* @param rulesPath
     * @return
     */
    public static List<String> parseAaptRulesGetXml(String rulesPath) {
        File aaptRules = new File(rulesPath)
        List<String> tmpXmlPath = new LinkedList<String>()
        for (String line : aaptRules.readLines()) {
            if (line.startsWith("# Referenced ") && !line.contains("AndroidManifest.xml")) {
                // aapt2
                String pathStr = line.replace("# Referenced at ", "").split(":")[0]
                if (pathStr != null && !pathStr.equals("") && !tmpXmlPath.contains(pathStr)) {
                    tmpXmlPath.add(pathStr)
                }
            }
        }
        return tmpXmlPath
    }

    public static List<String> parseWhiteList(String whiteListPath) {
        println "MessTag whiteListPath = " + whiteListPath
        File whiteListFile = new File(whiteListPath)
        List<String> whiteList = new LinkedList<String>()
        for (String line : whiteListFile.readLines()) {
            if (line != null && line.length() != 0 && !whiteList.contains(line)) {
                println "MessTag whiteList add " + line
                whiteList.add(line)
            }
        }
        return whiteList
    }
}
