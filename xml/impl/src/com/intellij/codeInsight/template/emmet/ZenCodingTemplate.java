// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.template.emmet;

import com.intellij.analysis.AnalysisBundle;
import com.intellij.application.options.emmet.EmmetOptions;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.codeInsight.template.CustomLiveTemplateBase;
import com.intellij.codeInsight.template.CustomTemplateCallback;
import com.intellij.codeInsight.template.LiveTemplateBuilder;
import com.intellij.codeInsight.template.TemplateActionContext;
import com.intellij.codeInsight.template.emmet.EmmetAbbreviationBalloon.EmmetContextHelp;
import com.intellij.codeInsight.template.emmet.filters.SingleLineEmmetFilter;
import com.intellij.codeInsight.template.emmet.filters.ZenCodingFilter;
import com.intellij.codeInsight.template.emmet.generators.XmlZenCodingGenerator;
import com.intellij.codeInsight.template.emmet.generators.ZenCodingGenerator;
import com.intellij.codeInsight.template.emmet.nodes.*;
import com.intellij.codeInsight.template.emmet.tokens.TemplateToken;
import com.intellij.codeInsight.template.emmet.tokens.TextToken;
import com.intellij.codeInsight.template.emmet.tokens.ZenCodingToken;
import com.intellij.codeInsight.template.impl.*;
import com.intellij.diagnostic.CoreAttachmentFactory;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorModificationUtil;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.patterns.StandardPatterns;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.xml.XmlBundle;
import com.intellij.xml.util.HtmlUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;


public class ZenCodingTemplate extends CustomLiveTemplateBase {
  public static final char MARKER = '\0';
  private static final String EMMET_RECENT_WRAP_ABBREVIATIONS_KEY = "emmet.recent.wrap.abbreviations";
  private static final String EMMET_LAST_WRAP_ABBREVIATIONS_KEY = "emmet.last.wrap.abbreviations";
  private static final Logger LOG = Logger.getInstance(ZenCodingTemplate.class);
  private static final EmmetContextHelp CONTEXT_HELP =
    new EmmetContextHelp(XmlBundle.messagePointer("zen.coding.context.help.tooltip"),
                         XmlBundle.messagePointer("zen.coding.context.help.link"),
                         "https://docs.emmet.io/actions/wrap-with-abbreviation/");

  @Nullable
  public static ZenCodingGenerator findApplicableDefaultGenerator(@NotNull CustomTemplateCallback callback, boolean wrapping) {
    PsiElement context = callback.getContext();
    if (!context.isValid()) {
      return null;
    }
    for (ZenCodingGenerator generator : ZenCodingGenerator.getInstances()) {
      if (generator.isMyContext(callback, wrapping) && generator.isAppliedByDefault(context)) {
        return generator;
      }
    }
    return null;
  }

  @Nullable
  public static ZenCodingNode parse(@NotNull String text,
                                    @NotNull CustomTemplateCallback callback,
                                    @NotNull ZenCodingGenerator generator,
                                    @Nullable String surroundedText) {
    List<ZenCodingToken> tokens = new EmmetLexer().lex(text);
    if (tokens == null) {
      return null;
    }
    if (!validate(tokens, generator)) {
      return null;
    }
    EmmetParser parser = generator.createParser(tokens, callback, generator, surroundedText != null);
    ZenCodingNode node = parser.parse();
    if (parser.getIndex() != tokens.size() || node instanceof TextNode) {
      return null;
    }
    return node;
  }

  private static boolean validate(@NotNull List<ZenCodingToken> tokens, @NotNull ZenCodingGenerator generator) {
    for (ZenCodingToken token : tokens) {
      if (token instanceof TextToken && !(generator instanceof XmlZenCodingGenerator)) {
        return false;
      }
    }
    return true;
  }

  public static boolean checkTemplateKey(@NotNull String key, CustomTemplateCallback callback, @NotNull ZenCodingGenerator generator) {
    return parse(key, callback, generator, null) != null;
  }

  @Override
  public void expand(@NotNull String key, @NotNull CustomTemplateCallback callback) {
    ZenCodingGenerator defaultGenerator = findApplicableDefaultGenerator(callback, false);
    if (defaultGenerator == null) {
      LOG.error("Cannot find defaultGenerator for key `" + key +"` at " + callback.getEditor().getCaretModel().getOffset() + " offset",
                CoreAttachmentFactory.createAttachment(callback.getEditor().getDocument()));
      return;
    }
    try {
      expand(key, callback, defaultGenerator, Collections.emptyList(), true, Registry.intValue("emmet.segments.limit"));
    }
    catch (EmmetException e) {
      CommonRefactoringUtil.showErrorHint(callback.getProject(), callback.getEditor(), e.getMessage(), XmlBundle.message("emmet.error"), "");
    }
  }

  @Nullable
  private static ZenCodingGenerator findApplicableGenerator(ZenCodingNode node, CustomTemplateCallback callback, boolean wrapping) {
    ZenCodingGenerator defaultGenerator = null;
    List<ZenCodingGenerator> generators = ZenCodingGenerator.getInstances();
    PsiElement context = callback.getContext();
    for (ZenCodingGenerator generator : generators) {
      if (generator.isMyContext(callback, wrapping) && generator.isAppliedByDefault(context)) {
        defaultGenerator = generator;
        break;
      }
    }
    while (node instanceof FilterNode filterNode) {
      String suffix = filterNode.getFilter();
      for (ZenCodingGenerator generator : generators) {
        if (generator.isMyContext(callback, wrapping)) {
          if (suffix != null && suffix.equals(generator.getSuffix())) {
            return generator;
          }
        }
      }
      node = filterNode.getNode();
    }
    return defaultGenerator;
  }

  @NotNull
  private static List<ZenCodingFilter> getFilters(ZenCodingNode node, PsiElement context) {
    List<ZenCodingFilter> result = new ArrayList<>();

    while (node instanceof FilterNode filterNode) {
      String filterSuffix = filterNode.getFilter();
      for (ZenCodingFilter filter : ZenCodingFilter.getInstances()) {
        if (filter.isMyContext(context) && filter.getSuffix().equals(filterSuffix)) {
          result.add(filter);
        }
      }
      node = filterNode.getNode();
    }

    for (ZenCodingFilter filter : ZenCodingFilter.getInstances()) {
      if (filter.isMyContext(context) && filter.isAppliedByDefault(context)) {
        result.add(filter);
      }
    }

    Collections.reverse(result);
    return result;
  }


  public static void expand(@NotNull String key, @NotNull CustomTemplateCallback callback,
                            @NotNull ZenCodingGenerator defaultGenerator,
                            @NotNull Collection<? extends ZenCodingFilter> extraFilters,
                            boolean expandPrimitiveAbbreviations, int segmentsLimit) throws EmmetException {
    final ZenCodingNode node = parse(key, callback, defaultGenerator, null);
    if (node == null) {
      return;
    }
    if (node instanceof TemplateNode) {
      if (key.equals(((TemplateNode)node).getTemplateToken().getKey()) && callback.findApplicableTemplates(key).size() > 1) {
        TemplateManagerImpl templateManager = (TemplateManagerImpl)callback.getTemplateManager();
        Map<TemplateImpl, String> template2Argument = templateManager.findMatchingTemplates(callback.getFile(), callback.getEditor(), null, TemplateSettings.getInstance());
        Runnable runnable = templateManager.startNonCustomTemplates(template2Argument, callback.getEditor(), null);
        if (runnable != null) {
          runnable.run();
        }
        return;
      }
    }

    PsiElement context = callback.getContext();
    ZenCodingGenerator generator = ObjectUtils.notNull(findApplicableGenerator(node, callback, false), defaultGenerator);
    List<ZenCodingFilter> filters = getFilters(node, context);
    filters.addAll(extraFilters);

    checkTemplateOutputLength(node, callback);

    callback.deleteTemplateKey(key);
    expand(node, generator, filters, null, callback, expandPrimitiveAbbreviations, segmentsLimit);
  }

  private static void expand(ZenCodingNode node,
                             ZenCodingGenerator generator,
                             List<ZenCodingFilter> filters,
                             String surroundedText,
                             CustomTemplateCallback callback, boolean expandPrimitiveAbbreviations, int segmentsLimit) throws EmmetException {

    checkTemplateOutputLength(node, callback);

    GenerationNode fakeParentNode = new GenerationNode(TemplateToken.EMPTY_TEMPLATE_TOKEN, -1, 1, surroundedText, true, null);
    node.expand(-1, 1, surroundedText, callback, true, fakeParentNode);

    if (!expandPrimitiveAbbreviations) {
      if (isPrimitiveNode(node)) {
        return;
      }
    }

    List<GenerationNode> genNodes = fakeParentNode.getChildren();
    LiveTemplateBuilder builder = new LiveTemplateBuilder(EmmetOptions.getInstance().isAddEditPointAtTheEndOfTemplate(), segmentsLimit);
    int end = -1;
    for (int i = 0, genNodesSize = genNodes.size(); i < genNodesSize; i++) {
      GenerationNode genNode = genNodes.get(i);
      TemplateImpl template = genNode.generate(callback, generator, filters, true, segmentsLimit);
      int e = builder.insertTemplate(builder.length(), template, null);
      if (i < genNodesSize - 1 && genNode.isInsertNewLineBetweenNodes()) {
        builder.insertText(e, "\n", false);
        e++;
      }
      if (end == -1 && end < builder.length()) {
        end = e;
      }
    }
    for (ZenCodingFilter filter : filters) {
      if (filter instanceof SingleLineEmmetFilter) {
        builder.setIsToReformat(false);
        break;
      }
    }
    callback.startTemplate(builder.buildTemplate(), null, null);
  }

  private static void checkTemplateOutputLength(ZenCodingNode node, CustomTemplateCallback callback) throws EmmetException {
    int predictedOutputLength = node.getApproximateOutputLength(callback);
    if (predictedOutputLength > 15 * 1024) {
      throw new EmmetException();
    }
  }

  private static boolean isPrimitiveNode(@NotNull ZenCodingNode node) {
    if (node instanceof TemplateNode) {
      final TemplateToken token = ((TemplateNode)node).getTemplateToken();
      if (token != null) {
        final Map<String, String> attributes = token.getAttributes();
        return attributes.isEmpty() ||
               attributes.containsKey(HtmlUtil.CLASS_ATTRIBUTE_NAME) && StringUtil.isEmpty(attributes.get(HtmlUtil.CLASS_ATTRIBUTE_NAME));
      }
    }
    return false;
  }

  @Override
  public void wrap(@NotNull final String selection, @NotNull final CustomTemplateCallback callback) {
    new EmmetAbbreviationBalloon(EMMET_RECENT_WRAP_ABBREVIATIONS_KEY, EMMET_LAST_WRAP_ABBREVIATIONS_KEY,
                                 new EmmetAbbreviationBalloon.Callback() {
                                   @Override
                                   public void onEnter(@NotNull String abbreviation) {
                                     doWrap(abbreviation, callback);
                                   }
                                 }, CONTEXT_HELP).show(callback);
  }

  public static boolean checkTemplateKey(String inputString, CustomTemplateCallback callback) {
    ZenCodingGenerator generator = findApplicableDefaultGenerator(callback, true);
    if (generator == null) {
      int offset = callback.getEditor().getCaretModel().getOffset();
      LOG.error("Emmet is disabled for context for file " + callback.getFileType().getName() + " in offset: " + offset,
                CoreAttachmentFactory.createAttachment(callback.getEditor().getDocument()));
      return false;
    }
    return checkTemplateKey(inputString, callback, generator);
  }

  @Override
  public boolean isApplicable(@NotNull CustomTemplateCallback callback, int offset, boolean wrapping) {
    final ZenCodingGenerator applicableGenerator = findApplicableDefaultGenerator(callback, wrapping);
    return applicableGenerator != null && applicableGenerator.isEnabled();
  }

  @Override
  public boolean hasCompletionItem(@NotNull CustomTemplateCallback callback, int offset) {
    final ZenCodingGenerator applicableGenerator = findApplicableDefaultGenerator(callback, false);
    return applicableGenerator != null && applicableGenerator.isEnabled() && applicableGenerator.hasCompletionItem();
  }

  public static void doWrap(@NotNull final String abbreviation, @NotNull final CustomTemplateCallback callback) {
    final ZenCodingGenerator defaultGenerator = findApplicableDefaultGenerator(callback, true);
    assert defaultGenerator != null;
    ApplicationManager.getApplication().runWriteAction(() -> CommandProcessor.getInstance().executeCommand(callback.getProject(), () -> callback.getEditor().getCaretModel().runForEachCaret(
      __ -> {
        String selectedText = callback.getEditor().getSelectionModel().getSelectedText();
        if (selectedText != null) {
          ZenCodingNode node = parse(abbreviation, callback, defaultGenerator, selectedText);
          assert node != null;
          PsiElement context = callback.getContext();
          ZenCodingGenerator generator = findApplicableGenerator(node, callback, true);
          List<ZenCodingFilter> filters = getFilters(node, context);

          EditorModificationUtil.deleteSelectedText(callback.getEditor());
          PsiDocumentManager.getInstance(callback.getProject()).commitAllDocuments();

          try {
            expand(node, generator, filters, selectedText, callback, true, Registry.intValue("emmet.segments.limit"));
          }
          catch (EmmetException e) {
            CommonRefactoringUtil.showErrorHint(callback.getProject(), callback.getEditor(), e.getMessage(),
                                                XmlBundle.message("emmet.error"), "");
          }
        }
      }), AnalysisBundle.message("insert.code.template.command"), null));
  }

  @Override
  @NotNull
  public String getTitle() {
    return XmlBundle.message("emmet.title");
  }

  @Override
  public char getShortcut() {
    return (char)EmmetOptions.getInstance().getEmmetExpandShortcut();
  }

  @Override
  public String computeTemplateKey(@NotNull CustomTemplateCallback callback) {
    ZenCodingGenerator generator = findApplicableDefaultGenerator(callback, false);
    if (generator == null) return null;
    return generator.computeTemplateKey(callback);
  }

  @Override
  public boolean supportsWrapping() {
    return true;
  }

  @Override
  public void addCompletions(CompletionParameters parameters, CompletionResultSet result) {
    if (!parameters.isAutoPopup()) {
      return;
    }

    PsiFile file = parameters.getPosition().getContainingFile();
    int offset = parameters.getOffset();
    Editor editor = parameters.getEditor();

    final CollectCustomTemplateCallback callback = new CollectCustomTemplateCallback(editor, file);
    ZenCodingGenerator generator = findApplicableDefaultGenerator(callback, false);
    if (generator != null && generator.addToCompletion()) {

      final String templatePrefix = computeTemplateKeyWithoutContextChecking(callback);

      if (templatePrefix != null) {
        List<TemplateImpl> regularTemplates = TemplateManagerImpl.listApplicableTemplates(
          TemplateActionContext.expanding(file, offset));
        boolean regularTemplateWithSamePrefixExists = !ContainerUtil.filter(regularTemplates,
                                                                            template -> templatePrefix.equals(template.getKey())).isEmpty();
        result = result.withPrefixMatcher(result.getPrefixMatcher().cloneWithPrefix(templatePrefix));
        result.restartCompletionOnPrefixChange(StandardPatterns.string().startsWith(templatePrefix));
        if (!regularTemplateWithSamePrefixExists) {
          // exclude perfect matches with existing templates because LiveTemplateCompletionContributor handles it
          final Collection<SingleLineEmmetFilter> extraFilters = ContainerUtil.newLinkedList(new SingleLineEmmetFilter());
          try {
            expand(templatePrefix, callback, generator, extraFilters, false, 0);
          }
          catch (EmmetException ignore) {
          }
          final TemplateImpl template = callback.getGeneratedTemplate();
          if (template != null) {
            template.setKey(templatePrefix);
            template.setDescription(template.getTemplateText());

            final CustomLiveTemplateLookupElement lookupElement =
              new CustomLiveTemplateLookupElement(this, template.getKey(), template.getKey(), template.getDescription(),
                                                  !LiveTemplateCompletionContributor.shouldShowAllTemplates(), true) {
                @Override
                public void renderElement(@NotNull LookupElementPresentation presentation) {
                  super.renderElement(presentation);
                  presentation.setTailText("\t Emmet abbreviation", true);
                }
              };

            result.addElement(lookupElement);
          }
        }
      }
      else if(result.getPrefixMatcher().getPrefix().isEmpty()) {
        result.restartCompletionOnPrefixChange(StandardPatterns.string().longerThan(0));
      }
    }
  }
}
