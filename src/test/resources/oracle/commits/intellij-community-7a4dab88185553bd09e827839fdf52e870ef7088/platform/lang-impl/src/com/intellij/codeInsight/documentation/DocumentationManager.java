/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.codeInsight.documentation;

import com.intellij.codeInsight.CodeInsightBundle;
import com.intellij.codeInsight.TargetElementUtilBase;
import com.intellij.codeInsight.hint.HintManagerImpl;
import com.intellij.codeInsight.hint.ParameterInfoController;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupEx;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.DataManager;
import com.intellij.ide.actions.BaseNavigateToSourceAction;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ide.util.gotoByName.ChooseByNameBase;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageDocumentation;
import com.intellij.lang.documentation.*;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionManagerEx;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.preview.PreviewManager;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.libraries.LibraryUtil;
import com.intellij.openapi.roots.ui.configuration.ProjectSettingsService;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.*;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ex.WindowManagerEx;
import com.intellij.psi.*;
import com.intellij.psi.presentation.java.SymbolPresentationUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.ui.ListScrollingUtil;
import com.intellij.ui.content.Content;
import com.intellij.ui.popup.AbstractPopup;
import com.intellij.ui.popup.PopupPositionManager;
import com.intellij.ui.popup.PopupUpdateProcessor;
import com.intellij.util.Alarm;
import com.intellij.util.BooleanFunction;
import com.intellij.util.Consumer;
import com.intellij.util.Processor;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.List;

public class DocumentationManager extends DockablePopupManager<DocumentationComponent> implements DocumentationManagerProtocol {

  @NonNls public static final String JAVADOC_LOCATION_AND_SIZE = "javadoc.popup";
  public static final DataKey<String> SELECTED_QUICK_DOC_TEXT = DataKey.create("QUICK_DOC.SELECTED_TEXT");

  private static final Logger LOG = Logger.getInstance("#" + DocumentationManager.class.getName());
  private static final String SHOW_DOCUMENTATION_IN_TOOL_WINDOW = "ShowDocumentationInToolWindow";
  private static final String DOCUMENTATION_AUTO_UPDATE_ENABLED = "DocumentationAutoUpdateEnabled";

  private Editor myEditor = null;
  private final Alarm myUpdateDocAlarm;
  private WeakReference<JBPopup> myDocInfoHintRef;
  private Component myPreviouslyFocused = null;
  public static final Key<SmartPsiElementPointer> ORIGINAL_ELEMENT_KEY = Key.create("Original element");

  private final ActionManagerEx myActionManagerEx;

  private static final int ourFlagsForTargetElements = TargetElementUtilBase.getInstance().getAllAccepted();

  private boolean myCloseOnSneeze;
  
  private ActionCallback myLastAction;
  private DocumentationComponent myTestDocumentationComponent;

  @Override
  protected String getToolwindowId() {
    return ToolWindowId.DOCUMENTATION;
  }

  @Override
  protected DocumentationComponent createComponent() {
    return new DocumentationComponent(this, createActions());
  }

  @Override
  protected String getRestorePopupDescription() {
    return "Restore popup view mode";
  }

  @Override
  protected String getAutoUpdateDescription() {
    return "Refresh documentation on selection change automatically";
  }

  @Override
  protected String getAutoUpdateTitle() {
    return "Auto-update from Source";
  }

  @NotNull
  @Override
  protected AnAction createRestorePopupAction() {
    AnAction restorePopupAction = super.createRestorePopupAction();
    ShortcutSet quickDocShortcut = ActionManager.getInstance().getAction(IdeActions.ACTION_QUICK_JAVADOC).getShortcutSet();
    restorePopupAction.registerCustomShortcutSet(quickDocShortcut, null);
    return restorePopupAction;
  }

  @Override
  protected void restorePopupBehavior() {
    if (myPreviouslyFocused != null) {
      IdeFocusManager.getInstance(myProject).requestFocus(myPreviouslyFocused, true);
    }
    super.restorePopupBehavior();
    updateComponent();
  }

  /**
   * @return    <code>true</code> if quick doc control is configured to not prevent user-IDE interaction (e.g. should be closed if
   *            the user presses a key);
   *            <code>false</code> otherwise
   */
  public boolean isCloseOnSneeze() {
    return myCloseOnSneeze;
  }
  
  public static DocumentationManager getInstance(Project project) {
    return ServiceManager.getService(project, DocumentationManager.class);
  }

  public DocumentationManager(final Project project, ActionManagerEx managerEx) {
    super(project);
    myActionManagerEx = managerEx;
    final AnActionListener actionListener = new AnActionListener() {
      @Override
      public void beforeActionPerformed(AnAction action, DataContext dataContext, AnActionEvent event) {
        final JBPopup hint = getDocInfoHint();
        if (hint != null) {
          if (action instanceof HintManagerImpl.ActionToIgnore) {
            ((AbstractPopup)hint).focusPreferredComponent();
            return;
          }
          if (action instanceof ListScrollingUtil.ListScrollAction) return;
          if (action == myActionManagerEx.getAction(IdeActions.ACTION_EDITOR_MOVE_CARET_DOWN)) return;
          if (action == myActionManagerEx.getAction(IdeActions.ACTION_EDITOR_MOVE_CARET_UP)) return;
          if (action == myActionManagerEx.getAction(IdeActions.ACTION_EDITOR_MOVE_CARET_PAGE_DOWN)) return;
          if (action == myActionManagerEx.getAction(IdeActions.ACTION_EDITOR_MOVE_CARET_PAGE_UP)) return;
          if (action == ActionManagerEx.getInstanceEx().getAction(IdeActions.ACTION_EDITOR_ESCAPE)) return;
          if (ActionPlaces.JAVADOC_INPLACE_SETTINGS.equals(event.getPlace())) return;
          if (action instanceof BaseNavigateToSourceAction) return;
          closeDocHint();
        }
      }

      @Override
      public void beforeEditorTyping(char c, DataContext dataContext) {
        final JBPopup hint = getDocInfoHint();
        if (hint != null && LookupManager.getActiveLookup(myEditor) == null) {
          hint.cancel();
        }
      }


      @Override
      public void afterActionPerformed(final AnAction action, final DataContext dataContext, AnActionEvent event) {
      }
    };
    myActionManagerEx.addAnActionListener(actionListener, project);
    myUpdateDocAlarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD,myProject);
  }

  private void closeDocHint() {
    JBPopup hint = getDocInfoHint();
    if (hint == null) {
      return;
    }
    myCloseOnSneeze = false;
    hint.cancel();
    Component toFocus = myPreviouslyFocused;
    hint.cancel();
    if (toFocus != null) {
      IdeFocusManager.getInstance(myProject).requestFocus(toFocus, true);
    }
  }

  public void setAllowContentUpdateFromContext(boolean allow) {
    if (hasActiveDockedDocWindow()) {
      restartAutoUpdate(allow);
    }
  }

  public void updateToolwindowContext() {
    if (hasActiveDockedDocWindow()) {
      updateComponent();
    }
  }

  public void showJavaDocInfoAtToolWindow(@NotNull PsiElement element, @NotNull PsiElement original) {
    final Content content = recreateToolWindow(element, original);
    if (content == null) return;

    fetchDocInfo(getDefaultCollector(element, original), (DocumentationComponent)content.getComponent(), true);
  }

  public void showJavaDocInfo(@NotNull final PsiElement element, final PsiElement original) {
    showJavaDocInfo(element, original, null);
  }

  /**
   * Asks to show quick doc for the target element.
   *
   * @param editor         editor with an element for which quick do should be shown
   * @param element        target element which documentation should be shown
   * @param original       element that was used as a quick doc anchor. Example: consider a code like {@code Runnable task;}.
   *                       A user wants to see javadoc for the {@code Runnable}, so, original element is a class name from the variable
   *                       declaration but <code>'element'</code> argument is a {@code Runnable} descriptor
   * @param closeCallback  callback to be notified on target hint close (if any)
   * @param closeOnSneeze  flag that defines whether quick doc control should be as non-obtrusive as possible. E.g. there are at least
   *                       two possible situations - the quick doc is shown automatically on mouse over element; the quick doc is shown
   *                       on explicit action call (Ctrl+Q). We want to close the doc on, say, editor viewport position change
   *                       at the first situation but don't want to do that at the second
   */
  public void showJavaDocInfo(@NotNull Editor editor,
                              @NotNull final PsiElement element,
                              @NotNull final PsiElement original,
                              @Nullable Runnable closeCallback,
                              boolean closeOnSneeze)
  {
    myEditor = editor;
    myCloseOnSneeze = closeOnSneeze;
    showJavaDocInfo(element, original, closeCallback);
  }
  
  public void showJavaDocInfo(@NotNull final PsiElement element,
                              final PsiElement original,
                              @Nullable Runnable closeCallback) {
    if (!element.isValid()) {
      return;
    }

    PopupUpdateProcessor updateProcessor = new PopupUpdateProcessor(element.getProject()) {
      @Override
      public void updatePopup(Object lookupItemObject) {
        if (lookupItemObject instanceof PsiElement) {
          doShowJavaDocInfo((PsiElement)lookupItemObject, false, this, original, null);
        }
      }
    };

    doShowJavaDocInfo(element, false, updateProcessor, original, closeCallback);
  }

  public void showJavaDocInfo(final Editor editor, @Nullable final PsiFile file, boolean requestFocus) {
    showJavaDocInfo(editor, file, requestFocus, null);
  }

  public void showJavaDocInfo(final Editor editor,
                              @Nullable final PsiFile file,
                              boolean requestFocus,
                              @Nullable final Runnable closeCallback) {
    myEditor = editor;
    final Project project = getProject(file);
    PsiDocumentManager.getInstance(project).commitAllDocuments();

    final PsiElement list =
      ParameterInfoController.findArgumentList(file, editor.getCaretModel().getOffset(), -1);
    PsiElement expressionList = null;
    if (list != null) {
      LookupEx lookup = LookupManager.getInstance(myProject).getActiveLookup();
      if (lookup != null) {
        expressionList = null; // take completion variants for documentation then
      } else {
        expressionList = list;
      }
    }

    final PsiElement originalElement = getContextElement(editor, file);
    PsiElement element = assertSameProject(findTargetElement(editor, file));

    if (element == null && expressionList != null) {
      element = expressionList;
    }

    if (element == null && file == null) return; //file == null for text field editor

    if (element == null) { // look if we are within a javadoc comment
      element = assertSameProject(originalElement);
      if (element == null) return;

      PsiComment comment = PsiTreeUtil.getParentOfType(element, PsiComment.class);
      if (comment == null) return;

      element = comment instanceof PsiDocCommentBase ? ((PsiDocCommentBase)comment).getOwner() : comment.getParent();
      if (element == null) return;
      //if (!(element instanceof PsiDocCommentOwner)) return null;
    }

    final PopupUpdateProcessor updateProcessor = new PopupUpdateProcessor(project) {
      @Override
      public void updatePopup(Object lookupIteObject) {
        if (lookupIteObject == null) {
          return;
        }
        if (lookupIteObject instanceof PsiElement) {
          doShowJavaDocInfo((PsiElement)lookupIteObject, false, this, originalElement, closeCallback);
          return;
        }

        DocumentationProvider documentationProvider = getProviderFromElement(file);

        PsiElement element = documentationProvider.getDocumentationElementForLookupItem(
          PsiManager.getInstance(myProject),
          lookupIteObject,
          originalElement
        );

        if (element == null) return;

        if (myEditor != null) {
          final PsiFile file = element.getContainingFile();
          if (file != null) {
            Editor editor = myEditor;
            showJavaDocInfo(myEditor, file, false);
            myEditor = editor;
          }
        }
        else {
          doShowJavaDocInfo(element, false, this, originalElement, closeCallback);
        }
      }
    };

    doShowJavaDocInfo(element, requestFocus, updateProcessor, originalElement, closeCallback);
  }

  public PsiElement findTargetElement(Editor editor, PsiFile file) {
    return findTargetElement(editor, file, getContextElement(editor, file));
  }

  private static PsiElement getContextElement(Editor editor, PsiFile file) {
    return file != null ? file.findElementAt(editor.getCaretModel().getOffset()) : null;
  }

  private void doShowJavaDocInfo(@NotNull final PsiElement element,
                                 boolean requestFocus,
                                 PopupUpdateProcessor updateProcessor,
                                 final PsiElement originalElement,
                                 @Nullable final Runnable closeCallback) {
    Project project = getProject(element);
    if (!project.isOpen()) return;

    storeOriginalElement(project, originalElement, element);

    myPreviouslyFocused = WindowManagerEx.getInstanceEx().getFocusedComponent(project);

    JBPopup _oldHint = getDocInfoHint();
    if (PreviewManager.SERVICE.preview(myProject, DocumentationPreviewPanelProvider.ID, Couple.of(element, originalElement), requestFocus) != null) {
      return;
    }

    if (myToolWindow == null && PropertiesComponent.getInstance().isTrueValue(SHOW_DOCUMENTATION_IN_TOOL_WINDOW)) {
      createToolWindow(element, originalElement);
    }
    else if (myToolWindow != null) {
      Content content = myToolWindow.getContentManager().getSelectedContent();
      if (content != null) {
        DocumentationComponent component = (DocumentationComponent)content.getComponent();
        boolean sameElement = element.getManager().areElementsEquivalent(component.getElement(), element);
        if (sameElement) {
          JComponent preferredFocusableComponent = content.getPreferredFocusableComponent();
          // focus toolwindow on the second actionPerformed
          boolean focus = requestFocus || CommandProcessor.getInstance().getCurrentCommand() != null;
          if (preferredFocusableComponent != null && focus) {
            IdeFocusManager.getInstance(myProject).requestFocus(preferredFocusableComponent, true);
          }
        }
        if (!sameElement || !component.isUpToDate()) {
          content.setDisplayName(getTitle(element, true));
          fetchDocInfo(getDefaultCollector(element, originalElement), component, true);
        }
      }

      if (!myToolWindow.isVisible()) {
        myToolWindow.show(null);
      }
    }
    else if (_oldHint != null && _oldHint.isVisible() && _oldHint instanceof AbstractPopup) {
      DocumentationComponent oldComponent = (DocumentationComponent)((AbstractPopup)_oldHint).getComponent();
      fetchDocInfo(getDefaultCollector(element, originalElement), oldComponent);
    }
    else {
      showInPopup(element, requestFocus, updateProcessor, originalElement, closeCallback);
    }
  }

  private void showInPopup(@NotNull final PsiElement element,
                           boolean requestFocus,
                           PopupUpdateProcessor updateProcessor,
                           final PsiElement originalElement,
                           @Nullable final Runnable closeCallback) {
    final DocumentationComponent component = myTestDocumentationComponent == null ? new DocumentationComponent(this) : 
                                             myTestDocumentationComponent;
    component.setNavigateCallback(new Consumer<PsiElement>() {
      @Override
      public void consume(PsiElement psiElement) {
        final AbstractPopup jbPopup = (AbstractPopup)getDocInfoHint();
        if (jbPopup != null) {
          final String title = getTitle(psiElement, false);
          jbPopup.setCaption(title);
        }
      }
    });
    Processor<JBPopup> pinCallback = new Processor<JBPopup>() {
      @Override
      public boolean process(JBPopup popup) {
        createToolWindow(element, originalElement);
        myToolWindow.setAutoHide(false);
        popup.cancel();
        return false;
      }
    };

    ActionListener actionListener = new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        createToolWindow(element, originalElement);
        final JBPopup hint = getDocInfoHint();
        if (hint != null && hint.isVisible()) hint.cancel();
      }
    };
    List<Pair<ActionListener, KeyStroke>> actions = ContainerUtil.newSmartList();
    AnAction quickDocAction = ActionManagerEx.getInstanceEx().getAction(IdeActions.ACTION_QUICK_JAVADOC);
    for (Shortcut shortcut : quickDocAction.getShortcutSet().getShortcuts()) {
      if (!(shortcut instanceof KeyboardShortcut)) continue;
      actions.add(Pair.create(actionListener, ((KeyboardShortcut)shortcut).getFirstKeyStroke()));
    }

    boolean hasLookup = LookupManager.getActiveLookup(myEditor) != null;
    final JBPopup hint = JBPopupFactory.getInstance().createComponentPopupBuilder(component, component)
      .setProject(element.getProject())
      .addListener(updateProcessor)
      .addUserData(updateProcessor)
      .setKeyboardActions(actions)
      .setDimensionServiceKey(myProject, JAVADOC_LOCATION_AND_SIZE, false)
      .setResizable(true)
      .setMovable(true)
      .setRequestFocus(requestFocus)
      .setCancelOnClickOutside(!hasLookup) // otherwise selecting lookup items by mouse would close the doc
      .setTitle(getTitle(element, false))
      .setCouldPin(pinCallback)
      .setModalContext(false)
      .setCancelCallback(new Computable<Boolean>() {
        @Override
        public Boolean compute() {
          myCloseOnSneeze = false;
          if (closeCallback != null) {
            closeCallback.run();
          }
          if (fromQuickSearch()) {
            ((ChooseByNameBase.JPanelProvider)myPreviouslyFocused.getParent()).unregisterHint();
          }

          Disposer.dispose(component);
          myEditor = null;
          myPreviouslyFocused = null;
          return Boolean.TRUE;
        }
      })
      .setKeyEventHandler(new BooleanFunction<KeyEvent>() {
        @Override
        public boolean fun(KeyEvent e) {
          if (myCloseOnSneeze) {
            closeDocHint();
          }
          if ((AbstractPopup.isCloseRequest(e) && getDocInfoHint() != null)) {
            closeDocHint();
            return true;
          }
          return false;
        }
      })
      .createPopup();

    component.setHint(hint);

    if (myEditor == null) {
      // subsequent invocation of javadoc popup from completion will have myEditor == null because of cancel invoked, 
      // so reevaluate the editor for proper popup placement
      Lookup lookup = LookupManager.getInstance(myProject).getActiveLookup();
      myEditor = lookup != null ? lookup.getEditor() : null;
    }
    fetchDocInfo(getDefaultCollector(element, originalElement), component);

    myDocInfoHintRef = new WeakReference<JBPopup>(hint);

    if (fromQuickSearch() && myPreviouslyFocused != null) {
      ((ChooseByNameBase.JPanelProvider)myPreviouslyFocused.getParent()).registerHint(hint);
    }
  }

  static String getTitle(@NotNull final PsiElement element, final boolean _short) {
    final String title = SymbolPresentationUtil.getSymbolPresentableText(element);
    return _short ? title != null ? title : element.getText() : CodeInsightBundle.message("javadoc.info.title", title != null ? title : element.getText());
  }

  public static void storeOriginalElement(final Project project, final PsiElement originalElement, final PsiElement element) {
    if (element == null) return;
    try {
      element.putUserData(
        ORIGINAL_ELEMENT_KEY,
        SmartPointerManager.getInstance(project).createSmartPsiElementPointer(originalElement)
      );
    } catch (RuntimeException ex) {
      // PsiPackage does not allow putUserData
    }
  }

  @Nullable
  public PsiElement findTargetElement(@NotNull final Editor editor, @Nullable final PsiFile file, PsiElement contextElement) {
    return findTargetElement(editor, editor.getCaretModel().getOffset(), file, contextElement);
  }
  
  @Nullable
  public PsiElement findTargetElement(final Editor editor, int offset, @Nullable final PsiFile file, PsiElement contextElement) {
    try {
      return findTargetElementUnsafe(editor, offset, file, contextElement);
    }
    catch (IndexNotReadyException inre) {
      LOG.warn("Index not ready");
      LOG.debug(inre);
      return null;
    }
  }

  /**
   * in case index is not ready will throw IndexNotReadyException
   */
  @Nullable
  private PsiElement findTargetElementUnsafe(final Editor editor, int offset, @Nullable final PsiFile file, PsiElement contextElement) {
    TargetElementUtilBase util = TargetElementUtilBase.getInstance();
    PsiElement element = assertSameProject(getElementFromLookup(editor, file));
    if (element == null && file != null) {
      final DocumentationProvider documentationProvider = getProviderFromElement(file);
      if (documentationProvider instanceof DocumentationProviderEx) {
        element = assertSameProject(((DocumentationProviderEx)documentationProvider).getCustomDocumentationElement(editor, file, contextElement));
      }
    }

    if (element == null) {
      element = assertSameProject(util.findTargetElement(editor, ourFlagsForTargetElements, offset));

      // Allow context doc over xml tag content
      if (element != null || contextElement != null) {
        final PsiElement adjusted = assertSameProject(util.adjustElement(editor, ourFlagsForTargetElements, element, contextElement));
        if (adjusted != null) {
          element = adjusted;
        }
      }
    }

    if (element == null) {
      final PsiReference ref = TargetElementUtilBase.findReference(editor, offset);
      if (ref != null) {
        element = assertSameProject(util.adjustReference(ref));
        if (ref instanceof PsiPolyVariantReference) {
          element = assertSameProject(ref.getElement());
        }
      }
    }

    storeOriginalElement(myProject, contextElement, element);

    return element;
  }

  @Nullable
  public PsiElement getElementFromLookup(final Editor editor, @Nullable final PsiFile file) {

    final Lookup activeLookup = LookupManager.getInstance(myProject).getActiveLookup();

    if (activeLookup != null) {
      LookupElement item = activeLookup.getCurrentItem();
      if (item != null) {


        int offset = editor.getCaretModel().getOffset();
        if (offset > 0 && offset == editor.getDocument().getTextLength()) offset--;
        PsiReference ref = TargetElementUtilBase.findReference(editor, offset);
        PsiElement contextElement = file == null? null : file.findElementAt(offset);
        PsiElement targetElement = ref != null ? ref.getElement() : contextElement;
        if (targetElement != null) {
          PsiUtilCore.ensureValid(targetElement);
        }

        DocumentationProvider documentationProvider = getProviderFromElement(file);

        PsiManager psiManager = PsiManager.getInstance(myProject);
        return documentationProvider.getDocumentationElementForLookupItem(psiManager, item.getObject(), targetElement);
      }
    }
    return null;
  }

  private boolean fromQuickSearch() {
    return myPreviouslyFocused != null && myPreviouslyFocused.getParent() instanceof ChooseByNameBase.JPanelProvider;
  }

  private DocumentationCollector getDefaultCollector(@NotNull final PsiElement element, @Nullable final PsiElement originalElement) {
    return new DefaultDocumentationCollector(element, originalElement);
  }

  @Nullable
  public JBPopup getDocInfoHint() {
    if (myDocInfoHintRef == null) return null;
    JBPopup hint = myDocInfoHintRef.get();
    if (hint == null || !hint.isVisible() && !ApplicationManager.getApplication().isUnitTestMode()) {
      myDocInfoHintRef = null;
      return null;
    }
    return hint;
  }

  public void fetchDocInfo(final DocumentationCollector provider, final DocumentationComponent component) {
    doFetchDocInfo(component, provider, true, false);
  }

  public void fetchDocInfo(final DocumentationCollector provider, final DocumentationComponent component, final boolean clearHistory) {
    doFetchDocInfo(component, provider, true, clearHistory);
  }

  public void fetchDocInfo(final PsiElement element, final DocumentationComponent component) {
    doFetchDocInfo(component, getDefaultCollector(element, null), true, false);
  }

  public ActionCallback queueFetchDocInfo(final DocumentationCollector provider, final DocumentationComponent component, final boolean clearHistory) {
    return doFetchDocInfo(component, provider, false, clearHistory);
  }

  public ActionCallback queueFetchDocInfo(final PsiElement element, final DocumentationComponent component) {
    return queueFetchDocInfo(getDefaultCollector(element, null), component, false);
  }

  private ActionCallback doFetchDocInfo(final DocumentationComponent component, final DocumentationCollector provider, final boolean cancelRequests, final boolean clearHistory) {
    final ActionCallback callback = new ActionCallback();
    myLastAction = callback;
    boolean wasEmpty = component.isEmpty();
    component.startWait();
    if (cancelRequests) {
      myUpdateDocAlarm.cancelAllRequests();
    }
    if (wasEmpty) {
      component.setText(CodeInsightBundle.message("javadoc.fetching.progress"), null, clearHistory);
      final AbstractPopup jbPopup = (AbstractPopup)getDocInfoHint();
      if (jbPopup != null) {
        jbPopup.setDimensionServiceKey(null);
      }
    }

    myUpdateDocAlarm.addRequest(new Runnable() {
      @Override
      public void run() {
        if (myProject.isDisposed()) return;
        final Throwable[] ex = new Throwable[1];
        String text = null;
        try {
          text = provider.getDocumentation();
        }
        catch (Throwable e) {
          LOG.info(e);
          ex[0] = e;
        }

        if (ex[0] != null) {
          //noinspection SSBasedInspection
          SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
              String message = ex[0] instanceof IndexNotReadyException
                             ? "Documentation is not available until indices are built."
                             : CodeInsightBundle.message("javadoc.external.fetch.error.message");
              component.setText(message, null, true);
              callback.setDone();
            }
          });
          return;
        }

        final PsiElement element = ApplicationManager.getApplication().runReadAction(new Computable<PsiElement>() {
          @Override
          @Nullable
          public PsiElement compute() {
            return provider.getElement();
          }
        });
        if (element == null) {
          return;
        }
        final String documentationText = text;
        //noinspection SSBasedInspection
        SwingUtilities.invokeLater(new Runnable() {
          @Override
          public void run() {
            PsiDocumentManager.getInstance(myProject).commitAllDocuments();

            if (!element.isValid()) {
              callback.setDone();
              return;
            }

            if (documentationText == null) {
              component.setText(CodeInsightBundle.message("no.documentation.found"), element, true);
            }
            else if (documentationText.length() == 0) {
              component.setText(component.getText(), element, true, clearHistory);
            }
            else {
              component.setData(element, documentationText, clearHistory, provider.getEffectiveExternalUrl());
            }

            final AbstractPopup jbPopup = (AbstractPopup)getDocInfoHint();
            if(jbPopup==null){
              callback.setDone();
              return;
            }
            else {
              jbPopup.setDimensionServiceKey(JAVADOC_LOCATION_AND_SIZE);
            }
            jbPopup.setCaption(getTitle(element, false));
            callback.setDone();
          }
        });
      }
    }, 10);
    return callback;
  }

  @NotNull 
  public static DocumentationProvider getProviderFromElement(final PsiElement element) {
    return getProviderFromElement(element, null);
  }

  @NotNull
  public static DocumentationProvider getProviderFromElement(@Nullable PsiElement element, @Nullable PsiElement originalElement) {
    if (element != null && !element.isValid()) {
      element = null;
    }
    if (originalElement != null && !originalElement.isValid()) {
      originalElement = null;
    }

    if (originalElement == null) {
      originalElement = getOriginalElement(element);
    }

    PsiFile containingFile =
      originalElement != null ? originalElement.getContainingFile() : element != null ? element.getContainingFile() : null;
    Set<DocumentationProvider> result = new LinkedHashSet<DocumentationProvider>();

    final Language containingFileLanguage = containingFile != null ? containingFile.getLanguage() : null;
    DocumentationProvider originalProvider =
      containingFile != null ? LanguageDocumentation.INSTANCE.forLanguage(containingFileLanguage) : null;

    final Language elementLanguage = element != null ? element.getLanguage() : null;
    DocumentationProvider elementProvider =
      element == null || elementLanguage.is(containingFileLanguage) ? null : LanguageDocumentation.INSTANCE.forLanguage(elementLanguage);

    result.add(elementProvider);
    result.add(originalProvider);

    if (containingFile != null) {
      final Language baseLanguage = containingFile.getViewProvider().getBaseLanguage();
      if (!baseLanguage.is(containingFileLanguage)) {
        result.add(LanguageDocumentation.INSTANCE.forLanguage(baseLanguage));
      }
    }
    else if (element instanceof PsiDirectory) {
      final Set<Language> langs = new HashSet<Language>();

      for (PsiFile file : ((PsiDirectory)element).getFiles()) {
        final Language baseLanguage = file.getViewProvider().getBaseLanguage();
        if (!langs.contains(baseLanguage)) {
          langs.add(baseLanguage);
          result.add(LanguageDocumentation.INSTANCE.forLanguage(baseLanguage));
        }
      }
    }
    return CompositeDocumentationProvider.wrapProviders(result);
  }

  @Nullable
  public static PsiElement getOriginalElement(final PsiElement element) {
    SmartPsiElementPointer originalElementPointer = element!=null ? element.getUserData(ORIGINAL_ELEMENT_KEY):null;
    return originalElementPointer != null ? originalElementPointer.getElement() : null;
  }

  void navigateByLink(final DocumentationComponent component, final String url) {
    component.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    final PsiElement psiElement = component.getElement();
    if (psiElement == null) {
      return;
    }
    final PsiManager manager = PsiManager.getInstance(getProject(psiElement));
    if (url.startsWith("open")) {
      final PsiFile containingFile = psiElement.getContainingFile();
      OrderEntry libraryEntry = null;
      if (containingFile != null) {
        final VirtualFile virtualFile = containingFile.getVirtualFile();
        libraryEntry = LibraryUtil.findLibraryEntry(virtualFile, myProject);
      }
      else if (psiElement instanceof PsiDirectoryContainer) {
        PsiDirectory[] directories = ((PsiDirectoryContainer)psiElement).getDirectories();
        for (PsiDirectory directory : directories) {
          final VirtualFile virtualFile = directory.getVirtualFile();
          libraryEntry = LibraryUtil.findLibraryEntry(virtualFile, myProject);
          if (libraryEntry != null) {
            break;
          }
        }
      }
      if (libraryEntry != null) {
        ProjectSettingsService.getInstance(myProject).openLibraryOrSdkSettings(libraryEntry);
      }
    } else if (url.startsWith(PSI_ELEMENT_PROTOCOL)) {
      final String refText = url.substring(PSI_ELEMENT_PROTOCOL.length());
      DocumentationProvider provider = getProviderFromElement(psiElement);
      PsiElement targetElement = provider.getDocumentationElementForLink(manager, refText, psiElement);
      if (targetElement == null) {
        for (DocumentationProvider documentationProvider : Extensions.getExtensions(DocumentationProvider.EP_NAME)) {
          targetElement = documentationProvider.getDocumentationElementForLink(manager, refText, psiElement);
          if (targetElement != null) {
            break;
          }
        }
      }
      if (targetElement == null) {
        for (Language language : Language.getRegisteredLanguages()) {
          DocumentationProvider documentationProvider = LanguageDocumentation.INSTANCE.forLanguage(language);
          if (documentationProvider != null) {
            targetElement = documentationProvider.getDocumentationElementForLink(manager, refText, psiElement);
            if (targetElement != null) {
              break;
            }
          }
        }
      }
      if (targetElement != null) {
        fetchDocInfo(getDefaultCollector(targetElement, null), component);
      }
    }
    else {
      final DocumentationProvider provider = getProviderFromElement(psiElement);
      boolean processed = false;
      if (provider instanceof CompositeDocumentationProvider) {
        for (DocumentationProvider p : ((CompositeDocumentationProvider)provider).getAllProviders()) {
          if (!(p instanceof ExternalDocumentationHandler)) continue;

          final ExternalDocumentationHandler externalHandler = (ExternalDocumentationHandler)p;
          if (externalHandler.canFetchDocumentationLink(url)) {
            fetchDocInfo(new DocumentationCollector() {
              @Override
              public String getDocumentation() throws Exception {
                return externalHandler.fetchExternalDocumentation(url, psiElement);
              }

              @Override
              public PsiElement getElement() {
                return psiElement;
              }

              @Nullable
              @Override
              public String getEffectiveExternalUrl() {
                return url;
              }
            }, component);
            processed = true;
          }
          else if (externalHandler.handleExternalLink(manager, url, psiElement)) {
            processed = true;
            break;
          }
        }
      }

      if (!processed) {

        fetchDocInfo
          (new DocumentationCollector() {
            @Override
            public String getDocumentation() throws Exception {
              if (url.startsWith(DOC_ELEMENT_PROTOCOL)) {
                final List<String> urls = ApplicationManager.getApplication().runReadAction(
                  new NullableComputable<List<String>>() {
                    @Override
                    public List<String> compute() {
                      final DocumentationProvider provider = getProviderFromElement(psiElement);
                      return provider.getUrlFor(psiElement, getOriginalElement(psiElement));
                    }
                  }
                );
                String url1 = urls != null && !urls.isEmpty() ? urls.get(0) : url;
                BrowserUtil.browse(url1);
              }
              else {
                BrowserUtil.browse(url);
              }
              return "";
            }

            @Override
            public PsiElement getElement() {
              //String loc = getElementLocator(docUrl);
              //
              //if (loc != null) {
              //  PsiElement context = component.getElement();
              //  return JavaDocUtil.findReferenceTarget(context.getManager(), loc, context);
              //}

              return psiElement;
            }

            @Nullable
            @Override
            public String getEffectiveExternalUrl() {
              return url;
            }
          }, component);
      }
    }

    component.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
  }

  void showHint(final JBPopup hint) {
    final Component focusOwner = IdeFocusManager.getInstance(myProject).getFocusOwner();
    DataContext dataContext = DataManager.getInstance().getDataContext(focusOwner);
    PopupPositionManager.positionPopupInBestPosition(hint, myEditor, dataContext);
  }

  public void requestFocus() {
    if (fromQuickSearch()) {
      myPreviouslyFocused.getParent().requestFocus();
    }
  }

  public Project getProject(@Nullable final PsiElement element) {
    assertSameProject(element);
    return myProject;
  }

  private PsiElement assertSameProject(@Nullable PsiElement element) {
    if (element != null && element.isValid() && myProject != element.getProject()) {
      throw new AssertionError(myProject + "!=" + element.getProject() + "; element=" + element);
    }
    return element;
  }

  public static void createHyperlink(StringBuilder buffer, String refText,String label,boolean plainLink) {
    DocumentationManagerUtil.createHyperlink(buffer, refText, label, plainLink);
  }

  @Override
  public String getShowInToolWindowProperty() {
    return SHOW_DOCUMENTATION_IN_TOOL_WINDOW;
  }

  @Override
  public String getAutoUpdateEnabledProperty() {
    return DOCUMENTATION_AUTO_UPDATE_ENABLED;
  }

  @Override
  protected void doUpdateComponent(PsiElement element, PsiElement originalElement, DocumentationComponent component) {
    fetchDocInfo(getDefaultCollector(element, originalElement), component);
  }
  
  @Override
  protected void doUpdateComponent(Editor editor, PsiFile psiFile) {
    showJavaDocInfo(editor, psiFile, false, null);
  }

  @Override
  protected void doUpdateComponent(@NotNull PsiElement element) {
    showJavaDocInfo(element, element, null);
  }

  @Override
  protected String getTitle(PsiElement element) {
    return getTitle(element, true);
  }

  @Nullable
  public Image getElementImage(@NotNull PsiElement element, @NotNull String imageSpec) {
    DocumentationProvider provider = getProviderFromElement(element);
    if (provider instanceof CompositeDocumentationProvider) {
      for (DocumentationProvider p : ((CompositeDocumentationProvider)provider).getAllProviders()) {
        if (p instanceof DocumentationProviderEx) {
          Image image = ((DocumentationProviderEx)p).getLocalImageForElement(element, imageSpec);
          if (image != null) return image;
        }
      }
    }
    return null;
  }
  
  @TestOnly
  public ActionCallback getLastAction() {
    return myLastAction;
  }
  
  @TestOnly
  public void setDocumentationComponent(DocumentationComponent documentationComponent) {
    myTestDocumentationComponent = documentationComponent;
  }

  private interface DocumentationCollector {
    @Nullable
    String getDocumentation() throws Exception;
    @Nullable
    PsiElement getElement();
    @Nullable
    String getEffectiveExternalUrl();
  }

  private class DefaultDocumentationCollector implements DocumentationCollector {

    private final PsiElement myElement;
    private final PsiElement myOriginalElement;

    private String myEffectiveUrl;

    public DefaultDocumentationCollector(PsiElement element, PsiElement originalElement) {
      myElement = element;
      myOriginalElement = originalElement;
    }

    @Override
    @Nullable
    public String getDocumentation() throws Exception {
      final DocumentationProvider provider = ApplicationManager.getApplication().runReadAction(
          new Computable<DocumentationProvider>() {
            @Override
            public DocumentationProvider compute() {
              return getProviderFromElement(myElement, myOriginalElement);
            }
          }
      );

      if (provider instanceof ExternalDocumentationProvider) {
        final List<String> urls = ApplicationManager.getApplication().runReadAction(
            new NullableComputable<List<String>>() {
              @Override
              public List<String> compute() {
                final SmartPsiElementPointer originalElementPtr = myElement.getUserData(ORIGINAL_ELEMENT_KEY);
                final PsiElement originalElement = originalElementPtr != null ? originalElementPtr.getElement() : null;
                if (((ExternalDocumentationProvider)provider).hasDocumentationFor(myElement, originalElement)) {
                  return provider.getUrlFor(myElement, originalElement);
                }
                return null;
              }
            }
        );
        if (urls != null) {
          for (String url : urls) {
            final String doc = ((ExternalDocumentationProvider)provider).fetchExternalDocumentation(myProject, myElement, Collections.singletonList(url));
            if (doc != null) {
              myEffectiveUrl = url;
              return doc;
            }
          }
        }
      }
      return ApplicationManager.getApplication().runReadAction(
          new Computable<String>() {
            @Override
            @Nullable
            public String compute() {
              final SmartPsiElementPointer originalElement = myElement.getUserData(ORIGINAL_ELEMENT_KEY);
              return provider.generateDoc(myElement, originalElement != null ? originalElement.getElement() : null);
            }
          }
      );
    }

    @Override
    @Nullable
    public PsiElement getElement() {
      return myElement.isValid() ? myElement : null;
    }

    @Nullable
    @Override
    public String getEffectiveExternalUrl() {
      return myEffectiveUrl;
    }
  }
}
