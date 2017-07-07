// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.android;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.actions.util.ActionsTestUtil.getFirstArtifactEndingWith;

import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionAction;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link AndroidInstrumentationTest}. */
@RunWith(JUnit4.class)
public class AndroidInstrumentationTestTest extends AndroidBuildViewTestCase {

  @Before
  public void setup() throws Exception {
    scratch.file(
        "java/com/app/BUILD",
        "android_binary(",
        "  name = 'app1',",
        "  manifest = 'AndroidManifest.xml',",
        ")",
        "android_binary(",
        "  name = 'app2',",
        "  manifest = 'AndroidManifest.xml',",
        ")",
        "android_binary(",
        "  name = 'support',",
        "  manifest = 'AndroidManifest.xml',",
        ")");
    scratch.file(
        "javatests/com/app/BUILD",
        "android_binary(",
        "  name = 'instrumentation_app1',",
        "  manifest = 'AndroidManifest.xml',",
        ")",
        "android_instrumentation(",
        "  name = 'instrumentation1',",
        "  target = '//java/com/app:app1',",
        "  instrumentation = ':instrumentation_app1',",
        ")",
        "android_binary(",
        "  name = 'instrumentation_app2',",
        "  manifest = 'AndroidManifest.xml',",
        ")",
        "android_instrumentation(",
        "  name = 'instrumentation2',",
        "  target = '//java/com/app:app2',",
        "  instrumentation = ':instrumentation_app2',",
        ")",
        "android_device_script_fixture(",
        "  name = 'device_fixture',",
        "  cmd = 'foo bar',",
        ")",
        "android_host_service_fixture(",
        "  name = 'host_fixture',",
        "  executable = '//java/com/server',",
        "  service_names = ['foo', 'bar'],",
        ")");
    scratch.file(
        "java/com/server/BUILD",
        "java_binary(",
        "  name = 'server',",
        "  main_class = 'does.not.exist',",
        "  srcs = [],",
        ")");
    scratch.file(
        "javatests/com/app/ait/BUILD",
        "android_instrumentation_test(",
        "  name = 'ait',",
        "  instrumentations = [",
        "    '//javatests/com/app:instrumentation1',",
        "    '//javatests/com/app:instrumentation2',",
        "  ],",
        "  target_device = '//tools/android/emulated_device:nexus_6',",
        "  fixtures = [",
        "    '//javatests/com/app:device_fixture',",
        "    '//javatests/com/app:host_fixture',",
        "  ],",
        "  support_apks = [",
        "    '//java/com/app:support',",
        "  ],",
        "  data = [",
        "    'foo.txt',",
        "  ],",
        ")");
    setupTargetDevice();
  }

  // TODO(ajmichael): Share this with AndroidDeviceTest.java
  private void setupTargetDevice() throws Exception {
    scratch.file(
        "tools/android/emulated_device/BUILD",
        "filegroup(",
        "  name = 'emulator_images_android_21_x86',",
        "  srcs = [",
        "    'android_21/x86/kernel-qemu',",
        "    'android_21/x86/ramdisk.img',",
        "    'android_21/x86/source.properties',",
        "    'android_21/x86/system.img.tar.gz',",
        "    'android_21/x86/userdata.img.tar.gz'",
        "  ],",
        ")",
        "android_device(",
        "  name = 'nexus_6',",
        "  ram = 2047,",
        "  horizontal_resolution = 720, ",
        "  vertical_resolution = 1280, ",
        "  cache = 32, ",
        "  system_image = ':emulator_images_android_21_x86',",
        "  screen_density = 280, ",
        "  vm_heap = 256",
        ")");
  }

  @Test
  public void testTestExecutableRunfiles() throws Exception {
    ConfiguredTarget androidInstrumentationTest = getConfiguredTarget("//javatests/com/app/ait");
    NestedSet<Artifact> runfiles =
        androidInstrumentationTest
            .getProvider(RunfilesProvider.class)
            .getDefaultRunfiles()
            .getAllArtifacts();
    assertThat(runfiles)
        .containsAllIn(
            getHostConfiguredTarget("//tools/android/emulated_device:nexus_6")
                .getProvider(RunfilesProvider.class)
                .getDefaultRunfiles()
                .getAllArtifacts());
    assertThat(runfiles)
        .containsAllIn(
            getHostConfiguredTarget("//java/com/server")
                .getProvider(RunfilesProvider.class)
                .getDefaultRunfiles()
                .getAllArtifacts());
    assertThat(runfiles)
        .containsAllIn(
            getHostConfiguredTarget(
                    androidInstrumentationTest
                        .getTarget()
                        .getAssociatedRule()
                        .getAttrDefaultValue("$test_entry_point")
                        .toString())
                .getProvider(RunfilesProvider.class)
                .getDefaultRunfiles()
                .getAllArtifacts());
    assertThat(runfiles)
        .containsAllOf(
            getDeviceFixtureScript(getConfiguredTarget("//javatests/com/app:device_fixture")),
            getInstrumentationApk(getConfiguredTarget("//javatests/com/app:instrumentation1")),
            getTargetApk(getConfiguredTarget("//javatests/com/app:instrumentation1")),
            getInstrumentationApk(getConfiguredTarget("//javatests/com/app:instrumentation2")),
            getTargetApk(getConfiguredTarget("//javatests/com/app:instrumentation2")),
            Iterables.getOnlyElement(
                getConfiguredTarget("//javatests/com/app/ait:foo.txt")
                    .getProvider(FileProvider.class)
                    .getFilesToBuild()));
  }

  @Test
  public void testTestExecutableContents() throws Exception {
    ConfiguredTarget androidInstrumentationTest = getConfiguredTarget("//javatests/com/app/ait");
    assertThat(androidInstrumentationTest).isNotNull();

    String testExecutableScript =
        ((TemplateExpansionAction)
                getGeneratingAction(
                    androidInstrumentationTest
                        .getProvider(FilesToRunProvider.class)
                        .getExecutable()))
            .getFileContents();

    assertThat(testExecutableScript)
        .contains(
            "instrumentation_apks=\"javatests/com/app/instrumentation1-instrumentation.apk "
                + "javatests/com/app/instrumentation2-instrumentation.apk\"");
    assertThat(testExecutableScript)
        .contains(
            "target_apks=\"javatests/com/app/instrumentation1-target.apk "
                + "javatests/com/app/instrumentation2-target.apk\"");
    assertThat(testExecutableScript).contains("support_apks=\"java/com/app/support.apk\"");
    assertThat(testExecutableScript)
        .contains(
            "declare -A device_script_fixtures=( "
                + "[javatests/com/app/cmd_device_fixtures/device_fixture/cmd.sh]=false,true )");
    assertThat(testExecutableScript).contains("host_service_fixture=\"java/com/server/server\"");
    assertThat(testExecutableScript).contains("host_service_fixture_services=\"foo,bar\"");
    assertThat(testExecutableScript)
        .contains("device_script=\"${WORKSPACE_DIR}/tools/android/emulated_device/nexus_6\"");
    assertThat(testExecutableScript).contains("data_deps=\"javatests/com/app/ait/foo.txt\"");
  }

  @Test
  public void testAtMostOneHostServiceFixture() throws Exception {
    checkError(
        "javatests/com/app/ait2",
        "ait",
        "android_instrumentation_test accepts at most one android_host_service_fixture",
        "android_host_service_fixture(",
        "  name = 'host_fixture',",
        "  executable = '//java/com/server',",
        "  service_names = ['foo', 'bar'],",
        ")",
        "android_instrumentation_test(",
        "  name = 'ait',",
        "  instrumentations = ['//javatests/com/app:instrumentation1'],",
        "  target_device = '//tools/android/emulated_device:nexus_6',",
        "  fixtures = [",
        "    ':host_fixture',",
        "    '//javatests/com/app:host_fixture',",
        "  ],",
        ")");
  }

  private static Artifact getDeviceFixtureScript(ConfiguredTarget deviceScriptFixture) {
    return getFirstArtifactEndingWith(
        deviceScriptFixture.getProvider(FileProvider.class).getFilesToBuild(), ".sh");
  }

  private static Artifact getInstrumentationApk(ConfiguredTarget instrumentation) {
    return ((AndroidInstrumentationInfoProvider)
            instrumentation.get(
                AndroidInstrumentationInfoProvider.ANDROID_INSTRUMENTATION_INFO.getKey()))
        .getInstrumentationApk();
  }

  private static Artifact getTargetApk(ConfiguredTarget instrumentation) {
    return ((AndroidInstrumentationInfoProvider)
            instrumentation.get(
                AndroidInstrumentationInfoProvider.ANDROID_INSTRUMENTATION_INFO.getKey()))
        .getTargetApk();
  }
}
