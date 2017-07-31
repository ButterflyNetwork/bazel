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
package com.google.devtools.build.android;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.android.builder.core.VariantType;
import com.android.manifmerger.ManifestMerger2;
import com.android.manifmerger.ManifestMerger2.Invoker;
import com.android.manifmerger.ManifestMerger2.Invoker.Feature;
import com.android.manifmerger.ManifestMerger2.MergeFailureException;
import com.android.manifmerger.ManifestMerger2.MergeType;
import com.android.manifmerger.ManifestMerger2.SystemProperty;
import com.android.manifmerger.MergingReport;
import com.android.manifmerger.MergingReport.MergedManifestKind;
import com.android.manifmerger.PlaceholderHandler;
import com.android.utils.Pair;
import com.android.utils.StdLogger;
import com.google.common.base.Function;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import javax.xml.stream.FactoryConfigurationError;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

/** Provides manifest processing oriented tools. */
public class AndroidManifestProcessor {
  private static final ImmutableMap<SystemProperty, String> SYSTEM_PROPERTY_NAMES =
      Maps.toMap(
          Arrays.asList(SystemProperty.values()),
          new Function<SystemProperty, String>() {
            @Override
            public String apply(SystemProperty property) {
              if (property == SystemProperty.PACKAGE) {
                return "applicationId";
              } else {
                return property.toCamelCase();
              }
            }
          });

  /** Exception encapsulating the error report of a manifest merge operation. */
  public static final class MergeErrorException extends Exception {
    private final MergingReport report;

    private MergeErrorException(MergingReport report) {
      super(report.getReportString());
      this.report = report;
    }

    public MergingReport getMergingReport() {
      return report;
    }
  }

  /** Creates a new processor with the appropriate logger. */
  public static AndroidManifestProcessor with(StdLogger stdLogger) {
    return new AndroidManifestProcessor(stdLogger);
  }

  private final StdLogger stdLogger;

  private AndroidManifestProcessor(StdLogger stdLogger) {
    this.stdLogger = stdLogger;
  }

  /**
   * Merge several manifests into one and perform placeholder substitutions. This operation uses
   * Gradle semantics.
   *
   * @param manifest The primary manifest of the merge.
   * @param mergeeManifests Manifests to be merged into {@code manifest}.
   * @param mergeType Whether the merger should operate in application or library mode.
   * @param values A map of strings to be used as manifest placeholders and overrides. packageName
   *     is the only disallowed value and will be ignored.
   * @param output The path to write the resultant manifest to.
   * @param logFile The path to write the merger log to.
   * @return The path of the resultant manifest, either {@code output}, or {@code manifest} if no
   *     merging was required.
   * @throws MergeErrorException if a merge error was encountered during merging.
   */
  // TODO(corysmith): Extract manifest processing.
  public Path mergeManifest(
      Path manifest,
      Map<Path, String> mergeeManifests,
      MergeType mergeType,
      Map<String, String> values,
      Path output,
      Path logFile) throws MergeErrorException {
    if (mergeeManifests.isEmpty() && values.isEmpty()) {
      return manifest;
    }

    Invoker<?> manifestMerger = ManifestMerger2.newMerger(manifest.toFile(), stdLogger, mergeType);
    MergedManifestKind mergedManifestKind = MergedManifestKind.MERGED;
    if (mergeType == MergeType.APPLICATION) {
      manifestMerger.withFeatures(Feature.REMOVE_TOOLS_DECLARATIONS);
    }

    // Add mergee manifests
    List<Pair<String, File>> libraryManifests = new ArrayList<>();
    for (Entry<Path, String> mergeeManifest : mergeeManifests.entrySet()) {
      libraryManifests.add(Pair.of(mergeeManifest.getValue(), mergeeManifest.getKey().toFile()));
    }
    manifestMerger.addLibraryManifests(libraryManifests);

    // Extract SystemProperties from the provided values.
    Map<String, Object> placeholders = new HashMap<>();
    placeholders.putAll(values);
    for (SystemProperty property : SystemProperty.values()) {
      if (values.containsKey(SYSTEM_PROPERTY_NAMES.get(property))) {
        manifestMerger.setOverride(
            property, values.get(SYSTEM_PROPERTY_NAMES.get(property)));

        // The manifest merger does not allow explicitly specifying either applicationId or
        // packageName as placeholders if SystemProperty.PACKAGE is specified. It forces these
        // placeholders to have the same value as specified by SystemProperty.PACKAGE.
        if (property == SystemProperty.PACKAGE) {
          placeholders.remove(PlaceholderHandler.APPLICATION_ID);
          placeholders.remove(PlaceholderHandler.PACKAGE_NAME);
        }
      }
    }

    // Add placeholders for all values.
    // packageName is populated from either the applicationId override or from the manifest itself;
    // it cannot be manually specified.
    placeholders.remove(PlaceholderHandler.PACKAGE_NAME);
    manifestMerger.setPlaceHolderValues(placeholders);

    try {
      MergingReport mergingReport = manifestMerger.merge();

      if (logFile != null) {
        logFile.getParent().toFile().mkdirs();
        try (PrintStream stream = new PrintStream(logFile.toFile())) {
          mergingReport.log(new AndroidResourceProcessor.PrintStreamLogger(stream));
        }
      }
      switch (mergingReport.getResult()) {
        case WARNING:
          mergingReport.log(stdLogger);
          Files.createDirectories(output.getParent());
          writeMergedManifest(mergedManifestKind, mergingReport, output);
          break;
        case SUCCESS:
          Files.createDirectories(output.getParent());
          writeMergedManifest(mergedManifestKind, mergingReport, output);
          break;
        case ERROR:
          mergingReport.log(stdLogger);
          throw new MergeErrorException(mergingReport);
        default:
          throw new RuntimeException("Unhandled result type : " + mergingReport.getResult());
      }
    } catch (IOException | MergeFailureException e) {
      throw new RuntimeException(e);
    }

    return output;
  }

  /**
   * Stamp specific properties into the manifest tag of the given manifest.
   *
   * @param variantType The type of rule the manifest belongs to, determining the stamping behavior.
   * @param customPackageForR The package attribute to stamp if not a binary manifest.
   * @param applicationId The package attribute for a binary manifest.
   * @param versionCode The android:versionCode attribute to stamp.
   * @param versionName The android:versionName attribute to stamp.
   * @param primaryData The {@link MergedAndroidData} that contains the manifest to modify.
   * @param processedManifest The path to write the modified manifest.
   * @return A {@link MergedAndroidData} containing the modified manifest, or {@code primaryData} if
   *     no modification was required.
   * @throws MergeErrorException if a merge error was encountered during merging.
   */
  public MergedAndroidData processManifest(
      VariantType variantType,
      String customPackageForR,
      String applicationId,
      int versionCode,
      String versionName,
      MergedAndroidData primaryData,
      Path processedManifest)
      throws MergeErrorException {

    ManifestMerger2.MergeType mergeType =
        variantType == VariantType.DEFAULT
            ? ManifestMerger2.MergeType.APPLICATION
            : ManifestMerger2.MergeType.LIBRARY;

    String newManifestPackage =
        variantType == VariantType.DEFAULT ? applicationId : customPackageForR;

    if (versionCode != -1 || versionName != null || newManifestPackage != null) {
      try {
        Files.createDirectories(processedManifest.getParent());

        // The generics on Invoker don't make sense, so ignore them.
        @SuppressWarnings("unchecked")
        Invoker<?> manifestMergerInvoker =
            ManifestMerger2.newMerger(primaryData.getManifest().toFile(), stdLogger, mergeType);
        // Stamp new package
        if (newManifestPackage != null) {
          manifestMergerInvoker.setOverride(SystemProperty.PACKAGE, newManifestPackage);
        }
        // Stamp version and applicationId (if provided) into the manifest
        if (versionCode > 0) {
          manifestMergerInvoker.setOverride(
              SystemProperty.VERSION_CODE, String.valueOf(versionCode));
        }
        if (versionName != null) {
          manifestMergerInvoker.setOverride(SystemProperty.VERSION_NAME, versionName);
        }

        MergedManifestKind mergedManifestKind = MergedManifestKind.MERGED;
        if (mergeType == ManifestMerger2.MergeType.APPLICATION) {
          manifestMergerInvoker.withFeatures(Invoker.Feature.REMOVE_TOOLS_DECLARATIONS);
        }

        MergingReport mergingReport = manifestMergerInvoker.merge();
        switch (mergingReport.getResult()) {
          case WARNING:
            mergingReport.log(stdLogger);
            writeMergedManifest(mergedManifestKind, mergingReport, processedManifest);
            break;
          case SUCCESS:
            writeMergedManifest(mergedManifestKind, mergingReport, processedManifest);
            break;
          case ERROR:
            mergingReport.log(stdLogger);
            throw new MergeErrorException(mergingReport);
          default:
            throw new RuntimeException("Unhandled result type : " + mergingReport.getResult());
        }
      } catch (IOException | MergeFailureException e) {
        throw new RuntimeException(e);
      }
      return new MergedAndroidData(
          primaryData.getResourceDir(), primaryData.getAssetDir(), processedManifest);
    }
    return primaryData;
  }

  /**
   * Overwrite the package attribute of {@code <manifest>} in an AndroidManifest.xml file.
   *
   * @param manifest The input manifest.
   * @param customPackage The package to write to the manifest.
   * @param output The output manifest to generate.
   * @return The output manifest if generated or the input manifest if no overwriting is required.
   */
  /* TODO(apell): switch from custom xml parsing to Gradle merger with NO_PLACEHOLDER_REPLACEMENT
   * set when android common is updated to version 2.5.0.
   */
  public Path writeManifestPackage(Path manifest, String customPackage, Path output) {
    if (Strings.isNullOrEmpty(customPackage)) {
      return manifest;
    }
    try {
      Files.createDirectories(output.getParent());
      XMLEventReader reader =
          XMLInputFactory.newInstance()
              .createXMLEventReader(Files.newInputStream(manifest), UTF_8.name());
      XMLEventWriter writer =
          XMLOutputFactory.newInstance()
              .createXMLEventWriter(Files.newOutputStream(output), UTF_8.name());
      XMLEventFactory eventFactory = XMLEventFactory.newInstance();
      while (reader.hasNext()) {
        XMLEvent event = reader.nextEvent();
        if (event.isStartElement()
            && event.asStartElement().getName().toString().equalsIgnoreCase("manifest")) {
          StartElement element = event.asStartElement();
          @SuppressWarnings("unchecked")
          Iterator<Attribute> attributes = element.getAttributes();
          ImmutableList.Builder<Attribute> newAttributes = ImmutableList.builder();
          while (attributes.hasNext()) {
            Attribute attr = attributes.next();
            if (attr.getName().toString().equalsIgnoreCase("package")) {
              newAttributes.add(eventFactory.createAttribute("package", customPackage));
            } else {
              newAttributes.add(attr);
            }
          }
          writer.add(
              eventFactory.createStartElement(
                  element.getName(), newAttributes.build().iterator(), element.getNamespaces()));
        } else {
          writer.add(event);
        }
      }
      writer.flush();
    } catch (XMLStreamException | FactoryConfigurationError | IOException e) {
      throw new RuntimeException(e);
    }

    return output;
  }

  private void writeMergedManifest(
      MergedManifestKind mergedManifestKind, MergingReport mergingReport, Path manifestOut)
      throws IOException {
    String manifestContents = mergingReport.getMergedDocument(mergedManifestKind);
    String annotatedDocument = mergingReport.getMergedDocument(MergedManifestKind.BLAME);
    stdLogger.verbose(annotatedDocument);
    Files.write(manifestOut, manifestContents.getBytes(UTF_8));
  }
}
