// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.apple;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.NativeProvider;
import java.util.Map;
import javax.annotation.Nullable;

/** A tuple containing information about a version of xcode and its properties. */
@Immutable
public class XcodeVersionProperties extends Info {

  /** Skylark name for the XcodeVersionProperties provider. */
  public static final String SKYLARK_NAME = "XcodeProperties";

  /** Skylark constructor and identifier for XcodeVersionProperties provider. */
  public static final NativeProvider<XcodeVersionProperties> SKYLARK_CONSTRUCTOR =
      new NativeProvider<XcodeVersionProperties>(XcodeVersionProperties.class, SKYLARK_NAME) {};

  @VisibleForTesting public static final String DEFAULT_IOS_SDK_VERSION = "8.4";
  @VisibleForTesting public static final String DEFAULT_WATCHOS_SDK_VERSION = "2.0";
  @VisibleForTesting public static final String DEFAULT_MACOS_SDK_VERSION = "10.10";
  @VisibleForTesting public static final String DEFAULT_TVOS_SDK_VERSION = "9.0";

  private final Optional<DottedVersion> xcodeVersion;
  private final DottedVersion defaultIosSdkVersion;
  private final DottedVersion defaultWatchosSdkVersion;
  private final DottedVersion defaultTvosSdkVersion;
  private final DottedVersion defaultMacosSdkVersion;

  /**
   * Creates and returns a tuple representing no known xcode property information (defaults are used
   * where applicable).
   */
  // TODO(bazel-team): The xcode version should be a well-defined value, either specified by the
  // user, evaluated on the local system, or set to a sensible default.
  // Unfortunately, until the local system evaluation hook is created, this constraint would break
  // some users.
  public static XcodeVersionProperties unknownXcodeVersionProperties() {
    return new XcodeVersionProperties(null);
  }

  /**
   * Constructor for when only the xcode version is specified, but no property information is
   * specified.
   */
  XcodeVersionProperties(DottedVersion xcodeVersion) {
    this(xcodeVersion, null, null, null, null);
  }

  /**
   * General constructor. Some (nullable) properties may be left unspecified. In these cases, a
   * semi-sensible default will be assigned to the property value.
   */
  XcodeVersionProperties(
      DottedVersion xcodeVersion,
      @Nullable String defaultIosSdkVersion,
      @Nullable String defaultWatchosSdkVersion,
      @Nullable String defaultTvosSdkVersion,
      @Nullable String defaultMacosSdkVersion) {
    super(
        SKYLARK_CONSTRUCTOR,
        getSkylarkFields(
            xcodeVersion,
            defaultIosSdkVersion,
            defaultWatchosSdkVersion,
            defaultTvosSdkVersion,
            defaultMacosSdkVersion));
    this.xcodeVersion = Optional.fromNullable(xcodeVersion);
    this.defaultIosSdkVersion =
        (Strings.isNullOrEmpty(defaultIosSdkVersion))
            ? DottedVersion.fromString(DEFAULT_IOS_SDK_VERSION)
            : DottedVersion.fromString(defaultIosSdkVersion);
    this.defaultWatchosSdkVersion =
        (Strings.isNullOrEmpty(defaultWatchosSdkVersion))
            ? DottedVersion.fromString(DEFAULT_WATCHOS_SDK_VERSION)
            : DottedVersion.fromString(defaultWatchosSdkVersion);
    this.defaultTvosSdkVersion =
        (Strings.isNullOrEmpty(defaultTvosSdkVersion))
            ? DottedVersion.fromString(DEFAULT_TVOS_SDK_VERSION)
            : DottedVersion.fromString(defaultTvosSdkVersion);
    this.defaultMacosSdkVersion =
        (Strings.isNullOrEmpty(defaultMacosSdkVersion))
            ? DottedVersion.fromString(DEFAULT_MACOS_SDK_VERSION)
            : DottedVersion.fromString(defaultMacosSdkVersion);
  }

  /** Returns the xcode version, or {@link Optional#absent} if the xcode version is unknown. */
  public Optional<DottedVersion> getXcodeVersion() {
    return xcodeVersion;
  }

  /** Returns the default ios sdk version to use if this xcode version is in use. */
  public DottedVersion getDefaultIosSdkVersion() {
    return defaultIosSdkVersion;
  }

  /** Returns the default watchos sdk version to use if this xcode version is in use. */
  public DottedVersion getDefaultWatchosSdkVersion() {
    return defaultWatchosSdkVersion;
  }

  /** Returns the default tvos sdk version to use if this xcode version is in use. */
  public DottedVersion getDefaultTvosSdkVersion() {
    return defaultTvosSdkVersion;
  }

  /** Returns the default macosx sdk version to use if this xcode version is in use. */
  public DottedVersion getDefaultMacosSdkVersion() {
    return defaultMacosSdkVersion;
  }

  private static Map<String, Object> getSkylarkFields(
      @Nullable DottedVersion xcodeVersion,
      @Nullable String defaultIosSdkVersion,
      @Nullable String defaultWatchosSdkVersion,
      @Nullable String defaultTvosSdkVersion,
      @Nullable String defaultMacosSdkVersion) {
    ImmutableMap.Builder<String, Object> skylarkFields = new ImmutableMap.Builder<>();
    if (xcodeVersion != null) {
      skylarkFields.put("xcode_version", xcodeVersion.toString());
    }

    if (defaultIosSdkVersion != null) {
      skylarkFields.put("default_ios_sdk_version", defaultIosSdkVersion);
    }

    if (defaultWatchosSdkVersion != null) {
      skylarkFields.put("default_watchos_sdk_version", defaultWatchosSdkVersion);
    }

    if (defaultTvosSdkVersion != null) {
      skylarkFields.put("default_tvos_sdk_version", defaultTvosSdkVersion);
    }

    if (defaultMacosSdkVersion != null) {
      skylarkFields.put("default_macos_sdk_version", defaultMacosSdkVersion);
    }

    return skylarkFields.build();
  }
}
