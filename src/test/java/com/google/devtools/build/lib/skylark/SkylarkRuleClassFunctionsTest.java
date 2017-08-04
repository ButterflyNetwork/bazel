// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.skylark;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.AdvertisedProviderSet;
import com.google.devtools.build.lib.packages.AspectParameters;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.ConfigurationTransition;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.NativeProvider;
import com.google.devtools.build.lib.packages.PredicateWithMessage;
import com.google.devtools.build.lib.packages.RequiredProviders;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.packages.SkylarkAspect;
import com.google.devtools.build.lib.packages.SkylarkAspectClass;
import com.google.devtools.build.lib.packages.SkylarkProvider;
import com.google.devtools.build.lib.packages.SkylarkProviderIdentifier;
import com.google.devtools.build.lib.rules.SkylarkAttr;
import com.google.devtools.build.lib.rules.SkylarkAttr.Descriptor;
import com.google.devtools.build.lib.rules.SkylarkFileType;
import com.google.devtools.build.lib.rules.SkylarkRuleClassFunctions.RuleFunction;
import com.google.devtools.build.lib.rules.SkylarkRuleContext;
import com.google.devtools.build.lib.skyframe.SkylarkImportLookupFunction;
import com.google.devtools.build.lib.skylark.util.SkylarkTestCase;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.ClassObject;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList.MutableList;
import com.google.devtools.build.lib.syntax.SkylarkList.Tuple;
import com.google.devtools.build.lib.syntax.SkylarkNestedSet;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.testutil.MoreAsserts;
import com.google.devtools.build.lib.util.FileTypeSet;
import java.util.Collection;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for SkylarkRuleClassFunctions.
 */
@RunWith(JUnit4.class)
public class SkylarkRuleClassFunctionsTest extends SkylarkTestCase {
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public final void createBuildFile() throws Exception  {
    scratch.file(
        "foo/BUILD",
        "genrule(name = 'foo',",
        "  cmd = 'dummy_cmd',",
        "  srcs = ['a.txt', 'b.img'],",
        "  tools = ['t.exe'],",
        "  outs = ['c.txt'])",
        "genrule(name = 'bar',",
        "  cmd = 'dummy_cmd',",
        "  srcs = [':jl', ':gl'],",
        "  outs = ['d.txt'])",
        "java_library(name = 'jl',",
        "  srcs = ['a.java'])",
        "genrule(name = 'gl',",
        "  cmd = 'touch $(OUTS)',",
        "  srcs = ['a.go'],",
        "  outs = [ 'gl.a', 'gl.gcgox', ],",
        "  output_to_bindir = 1,",
        ")");
  }

  @Test
  public void testCannotOverrideBuiltInAttribute() throws Exception {
    ev.setFailFast(false);
    try {
      evalAndExport(
          "def impl(ctx): return", "r = rule(impl, attrs = {'tags': attr.string_list()})");
      fail("Expected error '"
          + "There is already a built-in attribute 'tags' which cannot be overridden"
          + "' but got no error");
    } catch (AssertionError e) {
      assertThat(e)
          .hasMessageThat()
          .contains("There is already a built-in attribute 'tags' which cannot be overridden");
    }
  }

  @Test
  public void testCannotOverrideBuiltInAttributeName() throws Exception {
    ev.setFailFast(false);
    try {
      evalAndExport(
          "def impl(ctx): return", "r = rule(impl, attrs = {'name': attr.string()})");
      fail("Expected error '"
          + "There is already a built-in attribute 'name' which cannot be overridden"
          + "' but got no error");
    } catch (AssertionError e) {
      assertThat(e)
          .hasMessageThat()
          .contains("There is already a built-in attribute 'name' which cannot be overridden");
    }
  }

  @Test
  public void testImplicitArgsAttribute() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        "def _impl(ctx):",
        "  pass",
        "exec_rule = rule(implementation = _impl, executable = True)",
        "non_exec_rule = rule(implementation = _impl)");
    assertThat(getRuleClass("exec_rule").hasAttr("args", Type.STRING_LIST)).isTrue();
    assertThat(getRuleClass("non_exec_rule").hasAttr("args", Type.STRING_LIST)).isFalse();
  }

  private RuleClass getRuleClass(String name) throws Exception {
    return ((RuleFunction) lookup(name)).getRuleClass();
  }

  private void registerDummyUserDefinedFunction() throws Exception {
    eval("def impl():\n" + "  return 0\n");
  }

  @Test
  public void testAttrWithOnlyType() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string_list()");
    assertThat(attr.getType()).isEqualTo(Type.STRING_LIST);
  }

  private Attribute buildAttribute(String name, String... lines) throws Exception {
    String[] strings = lines.clone();
    strings[strings.length - 1] = String.format("%s = %s", name, strings[strings.length - 1]);
    evalAndExport(strings);
    Descriptor lookup = (Descriptor) ev.lookup(name);
    return lookup != null ? lookup.build(name) : null;
  }

  @Test
  public void testOutputListAttr() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.output_list()");
    assertThat(attr.getType()).isEqualTo(BuildType.OUTPUT_LIST);
  }

  @Test
  public void testIntListAttr() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.int_list()");
    assertThat(attr.getType()).isEqualTo(Type.INTEGER_LIST);
  }

  @Test
  public void testOutputAttr() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.output()");
    assertThat(attr.getType()).isEqualTo(BuildType.OUTPUT);
  }

  @Test
  public void testStringDictAttr() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string_dict(default = {'a': 'b'})");
    assertThat(attr.getType()).isEqualTo(Type.STRING_DICT);
  }

  @Test
  public void testStringListDictAttr() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string_list_dict(default = {'a': ['b', 'c']})");
    assertThat(attr.getType()).isEqualTo(Type.STRING_LIST_DICT);
  }

  @Test
  public void testAttrAllowedFileTypesAnyFile() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.label_list(allow_files = True)");
    assertThat(attr.getAllowedFileTypesPredicate()).isEqualTo(FileTypeSet.ANY_FILE);
  }

  @Test
  public void testAttrAllowedFileTypesWrongType() throws Exception {
    checkErrorContains(
        "allow_files should be a boolean or a string list",
        "attr.label_list(allow_files = 18)");
  }

  @Test
  public void testAttrAllowedSingleFileTypesWrongType() throws Exception {
    checkErrorContains(
        "allow_single_file should be a boolean or a string list",
        "attr.label(allow_single_file = 18)");
  }

  @Test
  public void testAttrWithList() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.label_list(allow_files = ['.xml'])");
    assertThat(attr.getAllowedFileTypesPredicate().apply("a.xml")).isTrue();
    assertThat(attr.getAllowedFileTypesPredicate().apply("a.txt")).isFalse();
    assertThat(attr.isSingleArtifact()).isFalse();
  }

  @Test
  public void testAttrSingleFileWithList() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.label(allow_single_file = ['.xml'])");
    assertThat(attr.getAllowedFileTypesPredicate().apply("a.xml")).isTrue();
    assertThat(attr.getAllowedFileTypesPredicate().apply("a.txt")).isFalse();
    assertThat(attr.isSingleArtifact()).isTrue();
  }

  @Test
  public void testAttrWithSkylarkFileType() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.label_list(allow_files = FileType(['.xml']))");
    assertThat(attr.getAllowedFileTypesPredicate().apply("a.xml")).isTrue();
    assertThat(attr.getAllowedFileTypesPredicate().apply("a.txt")).isFalse();
  }

  private static SkylarkProviderIdentifier legacy(String legacyId) {
    return SkylarkProviderIdentifier.forLegacy(legacyId);
  }

  private static SkylarkProviderIdentifier declared(String exportedName) {
    return SkylarkProviderIdentifier.forKey(
        new SkylarkProvider.SkylarkKey(FAKE_LABEL, exportedName));
  }

  @Test
  public void testAttrWithProviders() throws Exception {
    Attribute attr =
        buildAttribute("a1",
            "b = provider()",
            "attr.label_list(allow_files = True, providers = ['a', b])");
    assertThat(attr.getRequiredProviders().isSatisfiedBy(set(legacy("a"), declared("b")))).isTrue();
    assertThat(attr.getRequiredProviders().isSatisfiedBy(set(legacy("a")))).isFalse();
  }

  @Test
  public void testAttrWithProvidersOneEmpty() throws Exception {
    Attribute attr =
        buildAttribute(
            "a1",
            "b = provider()",
            "attr.label_list(allow_files = True, providers = [['a', b],[]])");
    assertThat(attr.getRequiredProviders().acceptsAny()).isTrue();
  }

  @Test
  public void testAttrWithProvidersList() throws Exception {
    Attribute attr =
        buildAttribute("a1",
            "b = provider()",
            "attr.label_list(allow_files = True, providers = [['a', b], ['c']])");
    assertThat(attr.getRequiredProviders().isSatisfiedBy(set(legacy("a"), declared("b")))).isTrue();
    assertThat(attr.getRequiredProviders().isSatisfiedBy(set(legacy("c")))).isTrue();
    assertThat(attr.getRequiredProviders().isSatisfiedBy(set(legacy("a")))).isFalse();
  }

  private static AdvertisedProviderSet set(SkylarkProviderIdentifier... ids) {
    AdvertisedProviderSet.Builder builder = AdvertisedProviderSet.builder();
    for (SkylarkProviderIdentifier id : ids) {
      builder.addSkylark(id);
    }
    return builder.build();
  }

  private void checkAttributeError(String expectedMessage, String... lines) throws Exception {
    ev.setFailFast(false);
    buildAttribute("fakeAttribute", lines);
    MoreAsserts.assertContainsEvent(ev.getEventCollector(), expectedMessage);
  }

  @Test
  public void testAttrWithWrongProvidersList() throws Exception {
    checkAttributeError(
        "element in 'providers' is of unexpected type. Either all elements should be providers,"
            + " or all elements should be lists of providers,"
            + " but got list with an element of type int.",
        "attr.label_list(allow_files = True,  providers = [['a', 1], ['c']])");

    checkAttributeError(
        "element in 'providers' is of unexpected type. Either all elements should be providers,"
            + " or all elements should be lists of providers,"
            + " but got an element of type string.",
        "b = provider()",
        "attr.label_list(allow_files = True,  providers = [['a', b], 'c'])");

    checkAttributeError(
        "element in 'providers' is of unexpected type. Either all elements should be providers,"
            + " or all elements should be lists of providers,"
            + " but got an element of type string.",
        "c = provider()",
        "attr.label_list(allow_files = True,  providers = [['a', b], c])");

  }

  @Test
  public void testLabelListWithAspects() throws Exception {
    evalAndExport(
            "def _impl(target, ctx):",
            "   pass",
            "my_aspect = aspect(implementation = _impl)",
            "a = attr.label_list(aspects = [my_aspect])");
    SkylarkAttr.Descriptor attr = (SkylarkAttr.Descriptor) ev.lookup("a");
            SkylarkAspect aspect = (SkylarkAspect) ev.lookup("my_aspect");
    assertThat(aspect).isNotNull();
    assertThat(attr.build("xxx").getAspectClasses()).containsExactly(aspect.getAspectClass());
  }

  @Test
  public void testLabelWithAspects() throws Exception {
    evalAndExport(
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(implementation = _impl)",
        "a = attr.label(aspects = [my_aspect])");
    SkylarkAttr.Descriptor attr = (SkylarkAttr.Descriptor) ev.lookup("a");
    SkylarkAspect aspect = (SkylarkAspect) ev.lookup("my_aspect");
    assertThat(aspect).isNotNull();
    assertThat(attr.build("xxx").getAspectClasses()).containsExactly(aspect.getAspectClass());
  }


  @Test
  public void testLabelListWithAspectsError() throws Exception {
    checkErrorContains(
        "expected type 'Aspect' for 'aspects' element but got type 'int' instead",
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(implementation = _impl)",
        "attr.label_list(aspects = [my_aspect, 123])");
  }

  @Test
  public void testAspectExtraDeps() throws Exception {
    evalAndExport(
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { '_extra_deps' : attr.label(default = Label('//foo/bar:baz')) }",
        ")");
    SkylarkAspect aspect = (SkylarkAspect) ev.lookup("my_aspect");
    Attribute attribute = Iterables.getOnlyElement(aspect.getAttributes());
    assertThat(attribute.getName()).isEqualTo("$extra_deps");
    assertThat(attribute.getDefaultValue(null))
        .isEqualTo(Label.parseAbsolute("//foo/bar:baz", false));
  }

  @Test
  public void testAspectParameter() throws Exception {
    evalAndExport(
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { 'param' : attr.string(values=['a', 'b']) }",
        ")");
    SkylarkAspect aspect = (SkylarkAspect) ev.lookup("my_aspect");
    Attribute attribute = Iterables.getOnlyElement(aspect.getAttributes());
    assertThat(attribute.getName()).isEqualTo("param");
  }

  @Test
  public void testAspectParameterRequiresValues() throws Exception {
    checkErrorContains(
        "Aspect parameter attribute 'param' must have type 'string' and use the 'values' "
        + "restriction.",
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { 'param' : attr.string(default = 'c') }",
        ")");
  }

  @Test
  public void testAspectParameterBadType() throws Exception {
    checkErrorContains(
        "Aspect parameter attribute 'param' must have type 'string' and use the 'values' "
        + "restriction.",
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { 'param' : attr.label(default = Label('//foo/bar:baz')) }",
        ")");
  }

  @Test
  public void testAspectParameterAndExtraDeps() throws Exception {
    evalAndExport(
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { 'param' : attr.string(values=['a', 'b']),",
        "             '_extra' : attr.label(default = Label('//foo/bar:baz')) }",
        ")");
    SkylarkAspect aspect = (SkylarkAspect) ev.lookup("my_aspect");
    assertThat(aspect.getAttributes()).hasSize(2);
    assertThat(aspect.getParamAttributes()).containsExactly("param");
  }

  @Test
  public void testAspectNoDefaultValueAttribute() throws Exception {
    checkErrorContains(
        "Aspect attribute '_extra_deps' has no default value",
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl,",
        "   attrs = { '_extra_deps' : attr.label() }",
        ")");
  }

  @Test
  public void testAspectAddToolchain() throws Exception {
    scratch.file("test/BUILD", "toolchain_type(name = 'my_toolchain_type')");
    evalAndExport(
        "def _impl(ctx): pass", "a1 = aspect(_impl, toolchains=['//test:my_toolchain_type'])");
    SkylarkAspect a = (SkylarkAspect) lookup("a1");
    assertThat(a.getRequiredToolchains()).containsExactly(makeLabel("//test:my_toolchain_type"));
  }

  @Test
  public void testNonLabelAttrWithProviders() throws Exception {
    checkErrorContains(
        "unexpected keyword 'providers' in call to string", "attr.string(providers = ['a'])");
  }

  private static final RuleClass.ConfiguredTargetFactory<Object, Object>
      DUMMY_CONFIGURED_TARGET_FACTORY =
          ruleContext -> {
            throw new IllegalStateException();
          };

  private RuleClass ruleClass(String name) {
    return new RuleClass.Builder(name, RuleClassType.NORMAL, false)
        .factory(DUMMY_CONFIGURED_TARGET_FACTORY)
        .add(Attribute.attr("tags", Type.STRING_LIST))
        .build();
  }

  @Test
  public void testAttrAllowedRuleClassesSpecificRuleClasses() throws Exception {
    Attribute attr = buildAttribute("a",
        "attr.label_list(allow_rules = ['java_binary'], allow_files = True)");
    assertThat(attr.getAllowedRuleClassesPredicate().apply(ruleClass("java_binary"))).isTrue();
    assertThat(attr.getAllowedRuleClassesPredicate().apply(ruleClass("genrule"))).isFalse();
  }

  @Test
  public void testAttrDefaultValue() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string(default = 'some value')");
    assertThat(attr.getDefaultValueForTesting()).isEqualTo("some value");
  }

  @Test
  public void testLabelAttrDefaultValueAsString() throws Exception {
    Attribute sligleAttr = buildAttribute("a1", "attr.label(default = '//foo:bar')");
    assertThat(sligleAttr.getDefaultValueForTesting())
        .isEqualTo(Label.parseAbsolute("//foo:bar", false));

    Attribute listAttr =
        buildAttribute("a2", "attr.label_list(default = ['//foo:bar', '//bar:foo'])");
    assertThat(listAttr.getDefaultValueForTesting())
        .isEqualTo(
            ImmutableList.of(
                Label.parseAbsolute("//foo:bar", false), Label.parseAbsolute("//bar:foo", false)));

    Attribute dictAttr =
        buildAttribute("a3", "attr.label_keyed_string_dict(default = {'//foo:bar': 'my value'})");
    assertThat(dictAttr.getDefaultValueForTesting())
        .isEqualTo(ImmutableMap.of(Label.parseAbsolute("//foo:bar", false), "my value"));
  }

  @Test
  public void testLabelAttrDefaultValueAsStringBadValue() throws Exception {
    checkErrorContains(
        "invalid label '/foo:bar' in parameter 'default' of attribute 'label': "
            + "invalid label: /foo:bar",
        "attr.label(default = '/foo:bar')");

    checkErrorContains(
        "invalid label '/bar:foo' in element 1 of parameter 'default' of attribute "
            + "'label_list': invalid label: /bar:foo",
        "attr.label_list(default = ['//foo:bar', '/bar:foo'])");

    checkErrorContains(
        "invalid label '/bar:foo' in dict key element: invalid label: /bar:foo",
        "attr.label_keyed_string_dict(default = {'//foo:bar': 'a', '/bar:foo': 'b'})");
  }

  @Test
  public void testAttrDefaultValueBadType() throws Exception {
    checkErrorContains(
        "argument 'default' has type 'int', but should be 'string'\n"
            + "in call to builtin function attr.string(*, default, doc, mandatory, values)",
        "attr.string(default = 1)");
  }

  @Test
  public void testAttrMandatory() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string(mandatory=True)");
    assertThat(attr.isMandatory()).isTrue();
    assertThat(attr.isNonEmpty()).isFalse();
  }

  @Test
  public void testAttrNonEmpty() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string_list(non_empty=True)");
    assertThat(attr.isNonEmpty()).isTrue();
    assertThat(attr.isMandatory()).isFalse();
  }

  @Test
  public void testAttrAllowEmpty() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string_list(allow_empty=False)");
    assertThat(attr.isNonEmpty()).isTrue();
    assertThat(attr.isMandatory()).isFalse();
  }

  @Test
  public void testAttrBadKeywordArguments() throws Exception {
    checkErrorContains(
        "unexpected keyword 'bad_keyword' in call to string", "attr.string(bad_keyword = '')");
  }

  @Test
  public void testAttrCfg() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.label(cfg = 'host', allow_files = True)");
    assertThat(attr.getConfigurationTransition()).isEqualTo(ConfigurationTransition.HOST);
  }

  @Test
  public void testAttrCfgData() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.label(cfg = 'data', allow_files = True)");
    assertThat(attr.getConfigurationTransition()).isEqualTo(ConfigurationTransition.DATA);
  }

  @Test
  public void testAttrCfgTarget() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.label(cfg = 'target', allow_files = True)");
    assertThat(attr.getConfigurationTransition()).isEqualTo(ConfigurationTransition.NONE);
  }

  @Test
  public void testAttrValues() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.string(values = ['ab', 'cd'])");
    PredicateWithMessage<Object> predicate = attr.getAllowedValues();
    assertThat(predicate.apply("ab")).isTrue();
    assertThat(predicate.apply("xy")).isFalse();
  }

  @Test
  public void testAttrIntValues() throws Exception {
    Attribute attr = buildAttribute("a1", "attr.int(values = [1, 2])");
    PredicateWithMessage<Object> predicate = attr.getAllowedValues();
    assertThat(predicate.apply(2)).isTrue();
    assertThat(predicate.apply(3)).isFalse();
  }

  @Test
  public void testAttrDoc() throws Exception {
    // We don't actually store the doc in the attr definition; right now it's just meant to be
    // extracted by documentation generating tools. So we don't have anything to assert and we just
    // verify that no exceptions were thrown from building them.
    buildAttribute("a1", "attr.bool(doc='foo')");
    buildAttribute("a2", "attr.int(doc='foo')");
    buildAttribute("a3", "attr.int_list(doc='foo')");
    buildAttribute("a4", "attr.label(doc='foo')");
    buildAttribute("a5", "attr.label_keyed_string_dict(doc='foo')");
    buildAttribute("a6", "attr.label_list(doc='foo')");
    buildAttribute("a7", "attr.license(doc='foo')");
    buildAttribute("a8", "attr.output(doc='foo')");
    buildAttribute("a9", "attr.output_list(doc='foo')");
    buildAttribute("a10", "attr.string(doc='foo')");
    buildAttribute("a11", "attr.string_dict(doc='foo')");
    buildAttribute("a12", "attr.string_list(doc='foo')");
    buildAttribute("a13", "attr.string_list_dict(doc='foo')");
  }

  @Test
  public void testAttrDocValueBadType() throws Exception {
    checkErrorContains(
        "argument 'doc' has type 'int', but should be 'string'\n"
            + "in call to builtin function attr.string(*, default, doc, mandatory, values)",
        "attr.string(doc = 1)");
  }

  @Test
  public void testRuleImplementation() throws Exception {
    evalAndExport("def impl(ctx): return None", "rule1 = rule(impl)");
    RuleClass c = ((RuleFunction) lookup("rule1")).getRuleClass();
    assertThat(c.getConfiguredTargetFunction().getName()).isEqualTo("impl");
  }

  @Test
  public void testRuleDoc() throws Exception {
    evalAndExport("def impl(ctx): return None", "rule1 = rule(impl, doc='foo')");
  }

  @Test
  public void testLateBoundAttrWorksWithOnlyLabel() throws Exception {
    checkEvalError(
        "argument 'default' has type 'function', but should be 'string'\n"
            + "in call to builtin function attr.string(*, default, doc, mandatory, values)",
        "def attr_value(cfg): return 'a'",
        "attr.string(default=attr_value)");
  }

  private static final Label FAKE_LABEL = Label.parseAbsoluteUnchecked("//fake/label.bzl");

  @Test
  public void testRuleAddAttribute() throws Exception {
    evalAndExport("def impl(ctx): return None", "r1 = rule(impl, attrs={'a1': attr.string()})");
    RuleClass c = ((RuleFunction) lookup("r1")).getRuleClass();
    assertThat(c.hasAttr("a1", Type.STRING)).isTrue();
  }

  protected void evalAndExport(String... lines) throws Exception {
    BuildFileAST buildFileAST = BuildFileAST.parseAndValidateSkylarkString(
        ev.getEnvironment(), lines);
    SkylarkImportLookupFunction.execAndExport(
        buildFileAST, FAKE_LABEL, ev.getEventHandler(), ev.getEnvironment());
  }

  @Test
  public void testExportAliasedName() throws Exception {
    // When there are multiple names aliasing the same SkylarkExportable, the first one to be
    // declared should be used. Make sure we're not using lexicographical order, hash order,
    // non-deterministic order, or anything else.
    evalAndExport(
        "def _impl(ctx): pass",
        "d = rule(implementation = _impl)",
        "a = d",
        // Having more names improves the chance that non-determinism will be caught.
        "b = d",
        "c = d",
        "e = d",
        "f = d",
        "foo = d",
        "bar = d",
        "baz = d",
        "x = d",
        "y = d",
        "z = d");
    String dName = ((RuleFunction) lookup("d")).getRuleClass().getName();
    String fooName = ((RuleFunction) lookup("foo")).getRuleClass().getName();
    assertThat(dName).isEqualTo("d");
    assertThat(fooName).isEqualTo("d");
  }

  @Test
  public void testOutputToGenfiles() throws Exception {
    evalAndExport("def impl(ctx): pass", "r1 = rule(impl, output_to_genfiles=True)");
    RuleClass c = ((RuleFunction) lookup("r1")).getRuleClass();
    assertThat(c.hasBinaryOutput()).isFalse();
  }

  @Test
  public void testRuleAddMultipleAttributes() throws Exception {
    evalAndExport(
        "def impl(ctx): return None",
        "r1 = rule(impl,",
        "     attrs = {",
        "            'a1': attr.label_list(allow_files=True),",
        "            'a2': attr.int()",
        "})");
    RuleClass c = ((RuleFunction) lookup("r1")).getRuleClass();
    assertThat(c.hasAttr("a1", BuildType.LABEL_LIST)).isTrue();
    assertThat(c.hasAttr("a2", Type.INTEGER)).isTrue();
  }
  @Test
  public void testRuleAttributeFlag() throws Exception {
    evalAndExport(
        "def impl(ctx): return None",
        "r1 = rule(impl, attrs = {'a1': attr.string(mandatory=True)})");
    RuleClass c = ((RuleFunction) lookup("r1")).getRuleClass();
    assertThat(c.getAttributeByName("a1").isMandatory()).isTrue();
  }

  @Test
  public void testRuleOutputs() throws Exception {
    evalAndExport(
        "def impl(ctx): return None",
        "r1 = rule(impl, outputs = {'a': 'a.txt'})");
    RuleClass c = ((RuleFunction) lookup("r1")).getRuleClass();
    ImplicitOutputsFunction function = c.getDefaultImplicitOutputsFunction();
    assertThat(function.getImplicitOutputs(null)).containsExactly("a.txt");
  }

  @Test
  public void testRuleUnknownKeyword() throws Exception {
    registerDummyUserDefinedFunction();
    checkErrorContains(
        "unexpected keyword 'bad_keyword' in call to " + "rule(implementation: function, ",
        "rule(impl, bad_keyword = 'some text')");
  }

  @Test
  public void testRuleImplementationMissing() throws Exception {
    checkErrorContains(
        "missing mandatory positional argument 'implementation' while calling "
            + "rule(implementation",
        "rule(attrs = {})");
  }

  @Test
  public void testRuleBadTypeForAdd() throws Exception {
    registerDummyUserDefinedFunction();
    checkErrorContains(
        "expected dict or NoneType for 'attrs' while calling rule but got string instead: "
            + "some text",
        "rule(impl, attrs = 'some text')");
  }

  @Test
  public void testRuleBadTypeInAdd() throws Exception {
    registerDummyUserDefinedFunction();
    checkErrorContains(
        "expected <String, Descriptor> type for 'attrs' but got <string, string> instead",
        "rule(impl, attrs = {'a1': 'some text'})");
  }

  @Test
  public void testRuleBadTypeForDoc() throws Exception {
    registerDummyUserDefinedFunction();
    checkErrorContains(
        "argument 'doc' has type 'int', but should be 'string'",
        "rule(impl, doc = 1)");
  }

  @Test
  public void testLabel() throws Exception {
    Object result = evalRuleClassCode("Label('//foo/foo:foo')");
    assertThat(result).isInstanceOf(Label.class);
    assertThat(result.toString()).isEqualTo("//foo/foo:foo");
  }

  @Test
  public void testLabelSameInstance() throws Exception {
    Object l1 = evalRuleClassCode("Label('//foo/foo:foo')");
    // Implicitly creates a new pkgContext and environment, yet labels should be the same.
    Object l2 = evalRuleClassCode("Label('//foo/foo:foo')");
    assertThat(l1).isSameAs(l2);
  }

  @Test
  public void testLabelNameAndPackage() throws Exception {
    Object result = evalRuleClassCode("Label('//foo/bar:baz').name");
    assertThat(result).isEqualTo("baz");
    // NB: implicitly creates a new pkgContext and environments, yet labels should be the same.
    result = evalRuleClassCode("Label('//foo/bar:baz').package");
    assertThat(result).isEqualTo("foo/bar");
  }

  @Test
  public void testRuleLabelDefaultValue() throws Exception {
    evalAndExport(
        "def impl(ctx): return None\n"
            + "r1 = rule(impl, attrs = {'a1': "
            + "attr.label(default = Label('//foo:foo'), allow_files=True)})");
    RuleClass c = ((RuleFunction) lookup("r1")).getRuleClass();
    Attribute a = c.getAttributeByName("a1");
    assertThat(a.getDefaultValueForTesting()).isInstanceOf(Label.class);
    assertThat(a.getDefaultValueForTesting().toString()).isEqualTo("//foo:foo");
  }

  @Test
  public void testIntDefaultValue() throws Exception {
    evalAndExport(
        "def impl(ctx): return None",
        "r1 = rule(impl, attrs = {'a1': attr.int(default = 40+2)})");
    RuleClass c = ((RuleFunction) lookup("r1")).getRuleClass();
    Attribute a = c.getAttributeByName("a1");
    assertThat(a.getDefaultValueForTesting()).isEqualTo(42);
  }

  @Test
  public void testFileType() throws Exception {
    Object result = evalRuleClassCode("FileType(['.css'])");
    SkylarkFileType fts = (SkylarkFileType) result;
    assertThat(fts.getExtensions()).isEqualTo(ImmutableList.of(".css"));
  }

  @Test
  public void testRuleInheritsBaseRuleAttributes() throws Exception {
    evalAndExport("def impl(ctx): return None", "r1 = rule(impl)");
    RuleClass c = ((RuleFunction) lookup("r1")).getRuleClass();
    assertThat(c.hasAttr("tags", Type.STRING_LIST)).isTrue();
    assertThat(c.hasAttr("visibility", BuildType.NODEP_LABEL_LIST)).isTrue();
    assertThat(c.hasAttr("deprecation", Type.STRING)).isTrue();
    assertThat(c.hasAttr(":action_listener", BuildType.LABEL_LIST))
        .isTrue(); // required for extra actions
  }

  private void checkTextMessage(String from, String... lines) throws Exception {
    Object result = evalRuleClassCode(from);
    assertThat(result).isEqualTo(Joiner.on("\n").join(lines) + "\n");
  }

  @Test
  public void testSimpleTextMessagesBooleanFields() throws Exception {
    checkTextMessage("struct(name=True).to_proto()", "name: true");
    checkTextMessage("struct(name=False).to_proto()", "name: false");
  }

  @Test
  public void testSimpleTextMessages() throws Exception {
    checkTextMessage("struct(name='value').to_proto()", "name: \"value\"");
    checkTextMessage("struct(name=['a', 'b']).to_proto()", "name: \"a\"", "name: \"b\"");
    checkTextMessage("struct(name=123).to_proto()", "name: 123");
    checkTextMessage("struct(name=[1, 2, 3]).to_proto()", "name: 1", "name: 2", "name: 3");
    checkTextMessage("struct(a=struct(b='b')).to_proto()", "a {", "  b: \"b\"", "}");
    checkTextMessage(
        "struct(a=[struct(b='x'), struct(b='y')]).to_proto()",
        "a {",
        "  b: \"x\"",
        "}",
        "a {",
        "  b: \"y\"",
        "}");
    checkTextMessage(
        "struct(a=struct(b=struct(c='c'))).to_proto()", "a {", "  b {", "    c: \"c\"", "  }", "}");
  }

  @Test
  public void testProtoFieldsOrder() throws Exception {
    checkTextMessage("struct(d=4, b=2, c=3, a=1).to_proto()", "a: 1", "b: 2", "c: 3", "d: 4");
  }

  @Test
  public void testTextMessageEscapes() throws Exception {
    checkTextMessage("struct(name='a\"b').to_proto()", "name: \"a\\\"b\"");
    checkTextMessage("struct(name='a\\'b').to_proto()", "name: \"a'b\"");
    checkTextMessage("struct(name='a\\nb').to_proto()", "name: \"a\\nb\"");

    // struct(name="a\\\"b") -> name: "a\\\"b"
    checkTextMessage("struct(name='a\\\\\\\"b').to_proto()", "name: \"a\\\\\\\"b\"");
  }

  @Test
  public void testTextMessageInvalidElementInListStructure() throws Exception {
    checkErrorContains(
        "Invalid text format, expected a struct, a string, a bool, or "
            + "an int but got a list for list element in struct field 'a'",
        "struct(a=[['b']]).to_proto()");
  }

  @Test
  public void testTextMessageInvalidStructure() throws Exception {
    checkErrorContains(
        "Invalid text format, expected a struct, a string, a bool, or an int "
            + "but got a function for struct field 'a'",
        "struct(a=rule).to_proto()");
  }

  private void checkJson(String from, String expected) throws Exception {
    Object result = evalRuleClassCode(from);
    assertThat(result).isEqualTo(expected);
  }

  @Test
  public void testJsonBooleanFields() throws Exception {
    checkJson("struct(name=True).to_json()", "{\"name\":true}");
    checkJson("struct(name=False).to_json()", "{\"name\":false}");
  }

  @Test
  public void testJsonEncoding() throws Exception {
    checkJson("struct(name='value').to_json()", "{\"name\":\"value\"}");
    checkJson("struct(name=['a', 'b']).to_json()", "{\"name\":[\"a\",\"b\"]}");
    checkJson("struct(name=123).to_json()", "{\"name\":123}");
    checkJson("struct(name=[1, 2, 3]).to_json()", "{\"name\":[1,2,3]}");
    checkJson("struct(a=struct(b='b')).to_json()", "{\"a\":{\"b\":\"b\"}}");
    checkJson("struct(a=[struct(b='x'), struct(b='y')]).to_json()",
        "{\"a\":[{\"b\":\"x\"},{\"b\":\"y\"}]}");
    checkJson("struct(a=struct(b=struct(c='c'))).to_json()",
        "{\"a\":{\"b\":{\"c\":\"c\"}}}");
  }

  @Test
  public void testJsonEscapes() throws Exception {
    checkJson("struct(name='a\"b').to_json()", "{\"name\":\"a\\\"b\"}");
    checkJson("struct(name='a\\'b').to_json()", "{\"name\":\"a'b\"}");
    checkJson("struct(name='a\\\\b').to_json()", "{\"name\":\"a\\\\b\"}");
    checkJson("struct(name='a\\nb').to_json()", "{\"name\":\"a\\nb\"}");
    checkJson("struct(name='a\\rb').to_json()", "{\"name\":\"a\\rb\"}");
    checkJson("struct(name='a\\tb').to_json()", "{\"name\":\"a\\tb\"}");
  }

  @Test
  public void testJsonNestedListStructure() throws Exception {
    checkJson("struct(a=[['b']]).to_json()", "{\"a\":[[\"b\"]]}");
  }

  @Test
  public void testJsonInvalidStructure() throws Exception {
    checkErrorContains(
        "Invalid text format, expected a struct, a string, a bool, or an int but got a "
            + "function for struct field 'a'",
        "struct(a=rule).to_json()");
  }

  @Test
  public void testLabelAttrWrongDefault() throws Exception {
    checkErrorContains(
        "expected value of type 'string' for parameter 'default' of attribute 'label', "
            + "but got 123 (int)",
        "attr.label(default = 123)");
  }

  @Test
  public void testLabelGetRelative() throws Exception {
    assertThat(eval("Label('//foo:bar').relative('baz')").toString()).isEqualTo("//foo:baz");
    assertThat(eval("Label('//foo:bar').relative('//baz:qux')").toString()).isEqualTo("//baz:qux");
  }

  @Test
  public void testLabelGetRelativeSyntaxError() throws Exception {
    checkErrorContains(
        "invalid target name 'bad//syntax': target names may not contain '//' path separators",
        "Label('//foo:bar').relative('bad//syntax')");
  }

  @Test
  public void testLicenseAttributesNonconfigurable() throws Exception {
    scratch.file("test/BUILD");
    scratch.file("test/rule.bzl",
        "def _impl(ctx):",
        "  return",
        "some_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {",
        "    'licenses': attr.license()",
        "  }",
        ")");
    scratch.file("third_party/foo/BUILD",
        "load('/test/rule', 'some_rule')",
        "some_rule(",
        "    name='r',",
        "    licenses = ['unencumbered']",
        ")");
    invalidatePackages();
    // Should succeed without a "licenses attribute is potentially configurable" loading error:
    createRuleContext("//third_party/foo:r");
  }

  @Test
  public void testStructCreation() throws Exception {
    // TODO(fwe): cannot be handled by current testing suite
    eval("x = struct(a = 1, b = 2)");
    assertThat(lookup("x")).isInstanceOf(ClassObject.class);
  }

  @Test
  public void testStructFields() throws Exception {
    // TODO(fwe): cannot be handled by current testing suite
    eval("x = struct(a = 1, b = 2)");
    ClassObject x = (ClassObject) lookup("x");
    assertThat(x.getValue("a")).isEqualTo(1);
    assertThat(x.getValue("b")).isEqualTo(2);
  }

  @Test
  public void testStructEquality() throws Exception {
    assertThat((Boolean) eval("struct(a = 1, b = 2) == struct(b = 2, a = 1)")).isTrue();
    assertThat((Boolean) eval("struct(a = 1) == struct(a = 1, b = 2)")).isFalse();
    assertThat((Boolean) eval("struct(a = 1, b = 2) == struct(a = 1)")).isFalse();
    // Compare a recursive object to itself to make sure reference equality is checked
    assertThat((Boolean) eval("s = (struct(a = 1, b = [])); s.b.append(s); s == s")).isTrue();
    assertThat((Boolean) eval("struct(a = 1, b = 2) == struct(a = 1, b = 3)")).isFalse();
    assertThat((Boolean) eval("struct(a = 1) == [1]")).isFalse();
    assertThat((Boolean) eval("[1] == struct(a = 1)")).isFalse();
    assertThat((Boolean) eval("struct() == struct()")).isTrue();
    assertThat((Boolean) eval("struct() == struct(a = 1)")).isFalse();

    eval("foo = provider(); bar = provider()");
    assertThat((Boolean) eval("struct(a = 1) == foo(a = 1)")).isFalse();
    assertThat((Boolean) eval("foo(a = 1) == struct(a = 1)")).isFalse();
    assertThat((Boolean) eval("foo(a = 1) == bar(a = 1)")).isFalse();
    assertThat((Boolean) eval("foo(a = 1) == foo(a = 1)")).isTrue();
  }

  @Test
  public void testStructIncomparability() throws Exception {
    checkErrorContains("Cannot compare structs", "struct(a = 1) < struct(a = 2)");
    checkErrorContains("Cannot compare structs", "struct(a = 1) > struct(a = 2)");
    checkErrorContains("Cannot compare structs", "struct(a = 1) <= struct(a = 2)");
    checkErrorContains("Cannot compare structs", "struct(a = 1) >= struct(a = 2)");
  }

  @Test
  public void testStructAccessingFieldsFromSkylark() throws Exception {
    eval("x = struct(a = 1, b = 2)", "x1 = x.a", "x2 = x.b");
    assertThat(lookup("x1")).isEqualTo(1);
    assertThat(lookup("x2")).isEqualTo(2);
  }

  @Test
  public void testStructAccessingUnknownField() throws Exception {
    checkErrorContains(
            "'struct' object has no attribute 'c'\n" + "Available attributes: a, b",
            "x = struct(a = 1, b = 2)",
            "y = x.c");
  }

  @Test
  public void testStructAccessingUnknownFieldWithArgs() throws Exception {
    checkErrorContains(
        "struct has no method 'c'", "x = struct(a = 1, b = 2)", "y = x.c()");
  }

  @Test
  public void testStructAccessingNonFunctionFieldWithArgs() throws Exception {
    checkErrorContains(
        "struct field 'a' is not a function", "x = struct(a = 1, b = 2)", "x1 = x.a(1)");
  }

  @Test
  public void testStructAccessingFunctionFieldWithArgs() throws Exception {
    eval("def f(x): return x+5", "x = struct(a = f, b = 2)", "x1 = x.a(1)");
    assertThat(lookup("x1")).isEqualTo(6);
  }

  @Test
  public void testStructPosArgs() throws Exception {
    checkErrorContains(
        "struct(**kwargs) does not accept positional arguments, but got 1", "x = struct(1, b = 2)");
  }

  @Test
  public void testStructConcatenationFieldNames() throws Exception {
    // TODO(fwe): cannot be handled by current testing suite
    eval("x = struct(a = 1, b = 2)",
        "y = struct(c = 1, d = 2)",
        "z = x + y\n");
    Info z = (Info) lookup("z");
    assertThat(z.getKeys()).isEqualTo(ImmutableSet.of("a", "b", "c", "d"));
  }

  @Test
  public void testStructConcatenationFieldValues() throws Exception {
    // TODO(fwe): cannot be handled by current testing suite
    eval("x = struct(a = 1, b = 2)",
        "y = struct(c = 1, d = 2)",
        "z = x + y\n");
    Info z = (Info) lookup("z");
    assertThat(z.getValue("a")).isEqualTo(1);
    assertThat(z.getValue("b")).isEqualTo(2);
    assertThat(z.getValue("c")).isEqualTo(1);
    assertThat(z.getValue("d")).isEqualTo(2);
  }

  @Test
  public void testStructConcatenationCommonFields() throws Exception {
    checkErrorContains("Cannot concat structs with common field(s): a",
        "x = struct(a = 1, b = 2)", "y = struct(c = 1, a = 2)", "z = x + y\n");
  }

  @Test
  public void testConditionalStructConcatenation() throws Exception {
    // TODO(fwe): cannot be handled by current testing suite
    eval("def func():",
        "  x = struct(a = 1, b = 2)",
        "  if True:",
        "    x += struct(c = 1, d = 2)",
        "  return x",
        "x = func()");
    Info x = (Info) lookup("x");
    assertThat(x.getValue("a")).isEqualTo(1);
    assertThat(x.getValue("b")).isEqualTo(2);
    assertThat(x.getValue("c")).isEqualTo(1);
    assertThat(x.getValue("d")).isEqualTo(2);
  }

  @Test
  public void testGetattrNoAttr() throws Exception {
    checkErrorContains(
        "object of type 'struct' has no attribute \"b\"", "s = struct(a='val')", "getattr(s, 'b')");
  }

  @Test
  public void testGetattr() throws Exception {
    eval(
        "s = struct(a='val')",
        "x = getattr(s, 'a')",
        "y = getattr(s, 'b', 'def')",
        "z = getattr(s, 'b', default = 'def')",
        "w = getattr(s, 'a', default='ignored')");
    assertThat(lookup("x")).isEqualTo("val");
    assertThat(lookup("y")).isEqualTo("def");
    assertThat(lookup("z")).isEqualTo("def");
    assertThat(lookup("w")).isEqualTo("val");
  }

  @Test
  public void testHasattr() throws Exception {
    eval("s = struct(a=1)",
        "x = hasattr(s, 'a')",
        "y = hasattr(s, 'b')\n");
    assertThat(lookup("x")).isEqualTo(true);
    assertThat(lookup("y")).isEqualTo(false);
  }

  @Test
  public void testStructStr() throws Exception {
    assertThat(eval("str(struct(x = 2, y = 3, z = 4))"))
        .isEqualTo("struct(x = 2, y = 3, z = 4)");
  }

  @Test
  public void testStructsInSets() throws Exception {
    eval("depset([struct(a='a')])");
  }

  @Test
  public void testStructsInDicts() throws Exception {
    eval("d = {struct(a = 1): 'aa', struct(b = 2): 'bb'}");
    assertThat(eval("d[struct(a = 1)]")).isEqualTo("aa");
    assertThat(eval("d[struct(b = 2)]")).isEqualTo("bb");
    assertThat(eval("str([d[k] for k in d])")).isEqualTo("[\"aa\", \"bb\"]");

    checkErrorContains(
        "unhashable type: 'struct'",
        "{struct(a = []): 'foo'}");
  }

  @Test
  public void testStructMembersAreImmutable() throws Exception {
    checkErrorContains(
        "cannot assign to 's.x'",
        "s = struct(x = 'a')",
        "s.x = 'b'\n");
  }

  @Test
  public void testStructDictMembersAreMutable() throws Exception {
    eval(
        "s = struct(x = {'a' : 1})",
        "s.x['b'] = 2\n");
    assertThat(((Info) lookup("s")).getValue("x")).isEqualTo(ImmutableMap.of("a", 1, "b", 2));
  }

  @Test
  public void testNsetGoodCompositeItem() throws Exception {
    eval("def func():", "  return depset([struct(a='a')])", "s = func()");
    Collection<Object> result = ((SkylarkNestedSet) lookup("s")).toCollection();
    assertThat(result).hasSize(1);
    assertThat(result.iterator().next()).isInstanceOf(Info.class);
  }

  @Test
  public void testNsetBadMutableItem() throws Exception {
    checkEvalError("depsets cannot contain mutable items", "depset([([],)])");
    checkEvalError("depsets cannot contain mutable items", "depset([struct(a=[])])");
  }

  private static Info makeStruct(String field, Object value) {
    return NativeProvider.STRUCT.create(ImmutableMap.of(field, value), "no field '%'");
  }

  private static Info makeBigStruct(Environment env) {
    // struct(a=[struct(x={1:1}), ()], b=(), c={2:2})
    return NativeProvider.STRUCT.create(
        ImmutableMap.<String, Object>of(
            "a",
                MutableList.<Object>of(
                    env,
                    NativeProvider.STRUCT.create(
                        ImmutableMap.<String, Object>of(
                            "x", SkylarkDict.<Object, Object>of(env, 1, 1)),
                        "no field '%s'"),
                    Tuple.of()),
            "b", Tuple.of(),
            "c", SkylarkDict.<Object, Object>of(env, 2, 2)),
        "no field '%s'");
  }

  @Test
  public void testStructMutabilityShallow() throws Exception {
    assertThat(EvalUtils.isImmutable(makeStruct("a", 1))).isTrue();
  }

  private static MutableList<Object> makeList(Environment env) {
    return MutableList.<Object>of(env, 1, 2, 3);
  }

  @Test
  public void testStructMutabilityDeep() throws Exception {
    assertThat(EvalUtils.isImmutable(Tuple.<Object>of(makeList(null)))).isTrue();
    assertThat(EvalUtils.isImmutable(makeStruct("a", makeList(null)))).isTrue();
    assertThat(EvalUtils.isImmutable(makeBigStruct(null))).isTrue();

    assertThat(EvalUtils.isImmutable(Tuple.<Object>of(makeList(ev.getEnvironment())))).isFalse();
    assertThat(EvalUtils.isImmutable(makeStruct("a", makeList(ev.getEnvironment())))).isFalse();
    assertThat(EvalUtils.isImmutable(makeBigStruct(ev.getEnvironment()))).isFalse();
  }

  @Test
  public void declaredProviders() throws Exception {
    evalAndExport(
        "data = provider()",
        "d = data(x = 1, y ='abc')",
        "d_x = d.x",
        "d_y = d.y"
    );
    assertThat(lookup("d_x")).isEqualTo(1);
    assertThat(lookup("d_y")).isEqualTo("abc");
    SkylarkProvider dataConstructor = (SkylarkProvider) lookup("data");
    Info data = (Info) lookup("d");
    assertThat(data.getProvider()).isEqualTo(dataConstructor);
    assertThat(dataConstructor.isExported()).isTrue();
    assertThat(dataConstructor.getPrintableName()).isEqualTo("data");
    assertThat(dataConstructor.getKey())
        .isEqualTo(new SkylarkProvider.SkylarkKey(FAKE_LABEL, "data"));
  }

  @Test
  public void declaredProvidersConcatSuccess() throws Exception {
    evalAndExport(
        "data = provider()",
        "dx = data(x = 1)",
        "dy = data(y = 'abc')",
        "dxy = dx + dy",
        "x = dxy.x",
        "y = dxy.y"
    );
    assertThat(lookup("x")).isEqualTo(1);
    assertThat(lookup("y")).isEqualTo("abc");
    SkylarkProvider dataConstructor = (SkylarkProvider) lookup("data");
    Info dx = (Info) lookup("dx");
    assertThat(dx.getProvider()).isEqualTo(dataConstructor);
    Info dy = (Info) lookup("dy");
    assertThat(dy.getProvider()).isEqualTo(dataConstructor);
  }

  @Test
  public void declaredProvidersConcatError() throws Exception {
    evalAndExport(
        "data1 = provider()",
        "data2 = provider()"
    );

    checkEvalError("Cannot concat data1 with data2",
        "d1 = data1(x = 1)",
        "d2 = data2(y = 2)",
        "d = d1 + d2"
    );
  }

  @Test
  public void structsAsDeclaredProvidersTest() throws Exception {
    evalAndExport(
        "data = struct(x = 1)"
    );
    Info data = (Info) lookup("data");
    assertThat(NativeProvider.STRUCT.isExported()).isTrue();
    assertThat(data.getProvider()).isEqualTo(NativeProvider.STRUCT);
    assertThat(data.getProvider().getKey()).isEqualTo(NativeProvider.STRUCT.getKey());
  }

  @Test
  public void declaredProvidersDoc() throws Exception {
    evalAndExport("data1 = provider(doc='foo')");
  }

  @Test
  public void declaredProvidersBadTypeForDoc() throws Exception {
    checkErrorContains(
        "argument 'doc' has type 'int', but should be 'string'",
        "provider(doc = 1)");
  }

  @Test
  public void aspectAllAttrs() throws Exception {
    evalAndExport(
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl, attr_aspects=['*'])");

    SkylarkAspect myAspect = (SkylarkAspect) lookup("my_aspect");
    assertThat(myAspect.getDefinition(AspectParameters.EMPTY).propagateAlong(
        Attribute.attr("foo", BuildType.LABEL).allowedFileTypes().build()
    )).isTrue();
  }

  @Test
  public void aspectRequiredAspectProvidersSingle() throws Exception {
    evalAndExport(
        "def _impl(target, ctx):",
        "   pass",
        "cc = provider()",
        "my_aspect = aspect(_impl, required_aspect_providers=['java', cc])"
    );
    SkylarkAspect myAspect = (SkylarkAspect) lookup("my_aspect");
    RequiredProviders requiredProviders = myAspect.getDefinition(AspectParameters.EMPTY)
        .getRequiredProvidersForAspects();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isTrue();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse();
    assertThat(requiredProviders.isSatisfiedBy(
        AdvertisedProviderSet.builder()
            .addSkylark(declared("cc"))
            .addSkylark("java")
            .build()))
        .isTrue();
    assertThat(requiredProviders.isSatisfiedBy(
        AdvertisedProviderSet.builder()
            .addSkylark("cc")
            .build()))
        .isFalse();
  }

  @Test
  public void aspectRequiredAspectProvidersAlternatives() throws Exception {
    evalAndExport(
        "def _impl(target, ctx):",
        "   pass",
        "cc = provider()",
        "my_aspect = aspect(_impl, required_aspect_providers=[['java'], [cc]])"
    );
    SkylarkAspect myAspect = (SkylarkAspect) lookup("my_aspect");
    RequiredProviders requiredProviders = myAspect.getDefinition(AspectParameters.EMPTY)
        .getRequiredProvidersForAspects();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isTrue();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse();
    assertThat(requiredProviders.isSatisfiedBy(
        AdvertisedProviderSet.builder()
            .addSkylark("java")
            .build()))
        .isTrue();
    assertThat(requiredProviders.isSatisfiedBy(
        AdvertisedProviderSet.builder()
            .addSkylark(declared("cc"))
            .build()))
        .isTrue();
    assertThat(requiredProviders.isSatisfiedBy(
        AdvertisedProviderSet.builder()
            .addSkylark("prolog")
            .build()))
        .isFalse();
  }

  @Test
  public void aspectRequiredAspectProvidersEmpty() throws Exception {
    evalAndExport(
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl, required_aspect_providers=[])"
    );
    SkylarkAspect myAspect = (SkylarkAspect) lookup("my_aspect");
    RequiredProviders requiredProviders = myAspect.getDefinition(AspectParameters.EMPTY)
        .getRequiredProvidersForAspects();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isFalse();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse();
  }

  @Test
  public void aspectRequiredAspectProvidersDefault() throws Exception {
    evalAndExport(
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl)"
    );
    SkylarkAspect myAspect = (SkylarkAspect) lookup("my_aspect");
    RequiredProviders requiredProviders = myAspect.getDefinition(AspectParameters.EMPTY)
        .getRequiredProvidersForAspects();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.ANY)).isFalse();
    assertThat(requiredProviders.isSatisfiedBy(AdvertisedProviderSet.EMPTY)).isFalse();
  }

  @Test
  public void aspectProvides() throws Exception {
    evalAndExport(
        "def _impl(target, ctx):",
        "   pass",
        "y = provider()",
        "my_aspect = aspect(_impl, provides = ['x', y])"
    );
    SkylarkAspect myAspect = (SkylarkAspect) lookup("my_aspect");
    AdvertisedProviderSet advertisedProviders = myAspect.getDefinition(AspectParameters.EMPTY)
        .getAdvertisedProviders();
    assertThat(advertisedProviders.canHaveAnyProvider()).isFalse();
    assertThat(advertisedProviders.getSkylarkProviders())
        .containsExactly(legacy("x"), declared("y"));
  }

  @Test
  public void aspectProvidesError() throws Exception {
    ev.setFailFast(false);
    evalAndExport(
        "def _impl(target, ctx):",
        "   pass",
        "y = provider()",
        "my_aspect = aspect(_impl, provides = ['x', 1])"
    );
    MoreAsserts.assertContainsEvent(ev.getEventCollector(),
        " Illegal argument: element in 'provides' is of unexpected type."
            + " Should be list of providers, but got int. ");
  }

  @Test
  public void aspectDoc() throws Exception {
    evalAndExport(
        "def _impl(target, ctx):",
        "   pass",
        "my_aspect = aspect(_impl, doc='foo')");
  }

  @Test
  public void aspectBadTypeForDoc() throws Exception {
    registerDummyUserDefinedFunction();
    checkErrorContains(
        "argument 'doc' has type 'int', but should be 'string'",
        "aspect(impl, doc = 1)");
  }



  @Test
  public void fancyExports() throws Exception {
    evalAndExport(
        "def _impla(target, ctx): pass",
        "p, (a, p1) = [",
        "   provider(),",
        "   [ aspect(_impla),",
        "     provider() ]",
        "]"
    );
    SkylarkProvider p = (SkylarkProvider) lookup("p");
    SkylarkAspect a = (SkylarkAspect) lookup("a");
    SkylarkProvider p1 = (SkylarkProvider) lookup("p1");
    assertThat(p.getPrintableName()).isEqualTo("p");
    assertThat(p.getKey()).isEqualTo(new SkylarkProvider.SkylarkKey(FAKE_LABEL, "p"));
    assertThat(p1.getPrintableName()).isEqualTo("p1");
    assertThat(p1.getKey()).isEqualTo(new SkylarkProvider.SkylarkKey(FAKE_LABEL, "p1"));
    assertThat(a.getAspectClass()).isEqualTo(
        new SkylarkAspectClass(FAKE_LABEL, "a")
    );
  }

  @Test
  public void multipleTopLevels() throws Exception {
    evalAndExport(
        "p = provider()",
        "p1 = p"
    );
    SkylarkProvider p = (SkylarkProvider) lookup("p");
    SkylarkProvider p1 = (SkylarkProvider) lookup("p1");
    assertThat(p).isEqualTo(p1);
    assertThat(p.getKey()).isEqualTo(new SkylarkProvider.SkylarkKey(FAKE_LABEL, "p"));
    assertThat(p1.getKey()).isEqualTo(new SkylarkProvider.SkylarkKey(FAKE_LABEL, "p"));
  }


  @Test
  public void starTheOnlyAspectArg() throws Exception {
    checkEvalError("'*' must be the only string in 'attr_aspects' list",
        "def _impl(target, ctx):",
        "   pass",
        "aspect(_impl, attr_aspects=['*', 'foo'])");
  }

  @Test
  public void testMandatoryConfigParameterForExecutableLabels() throws Exception {
    scratch.file("third_party/foo/extension.bzl",
      "def _main_rule_impl(ctx):",
      "    pass",
      "my_rule = rule(_main_rule_impl,",
      "    attrs = { ",
      "        'exe' : attr.label(executable = True, allow_files = True),",
      "    },",
      ")"
    );
    scratch.file("third_party/foo/BUILD",
      "load('extension',  'my_rule')",
      "my_rule(name = 'main', exe = ':tool.sh')"
    );

    try {
      createRuleContext("//third_party/foo:main");
      fail();
    } catch (AssertionError e) {
      assertThat(e)
          .hasMessageThat()
          .contains("cfg parameter is mandatory when executable=True is " + "provided.");
    }
  }

  @Test
  public void testRuleAddToolchain() throws Exception {
    scratch.file("test/BUILD", "toolchain_type(name = 'my_toolchain_type')");
    evalAndExport(
        "def impl(ctx): return None", "r1 = rule(impl, toolchains=['//test:my_toolchain_type'])");
    RuleClass c = ((RuleFunction) lookup("r1")).getRuleClass();
    assertThat(c.getRequiredToolchains()).containsExactly(makeLabel("//test:my_toolchain_type"));
  }

  @Test
  public void testRuleFunctionReturnsNone() throws Exception {
    scratch.file("test/rule.bzl",
        "def _impl(ctx):",
        "  pass",
        "foo_rule = rule(",
        "  implementation = _impl,",
        "  attrs = {'params': attr.string_list()},",
        ")");
    scratch.file("test/BUILD",
        "load(':rule.bzl', 'foo_rule')",
        "r = foo_rule(name='foo')",  // Custom rule should return None
        "c = cc_library(name='cc')", // Native rule should return None
        "",
        "foo_rule(",
        "    name='check',",
        "    params = [type(r), type(c)]",
        ")");
    invalidatePackages();
    SkylarkRuleContext context = createRuleContext("//test:check");
    @SuppressWarnings("unchecked")
    MutableList<Object> params = (MutableList<Object>) context.getAttr().getValue("params");
    assertThat(params.get(0)).isEqualTo("NoneType");
    assertThat(params.get(1)).isEqualTo("NoneType");
  }
}
