/*
 * Copyright 2012 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.errorprone;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;

import com.google.common.base.Objects;
import com.google.common.base.Throwables;
import com.google.errorprone.scanner.Scanner;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskEvent.Kind;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.api.MultiTaskListener;
import com.sun.tools.javac.code.Symbol.CompletionFailure;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * Used to run an error-prone analysis as a phase in the javac compiler.
 */
public class ErrorProneAnalyzer implements TaskListener {

  public static ErrorProneAnalyzer create(Scanner scanner) {
    checkNotNull(scanner);
    return new ErrorProneAnalyzer(scanner);
  }

  /**
   * Initializes the analyzer with the current compilation context. (E.g. for the current
   * annotation processing round.)
   */
  public ErrorProneAnalyzer init(Context context) {
    this.initialized = true;
    this.context = context;
    this.log = Log.instance(context);
    this.compiler = JavaCompiler.instance(context);
    return this;
  }

  public ErrorProneAnalyzer register(Context context) {
    init(context);
    MultiTaskListener.instance(context).add(this);
    return this;
  }

  private final Scanner errorProneScanner;
  // The set of trees that have already been scanned.
  private final Set<Tree> seen = new HashSet<>();

  private Context context;
  private Log log;
  private JavaCompiler compiler;
  private boolean initialized = false;

  private ErrorProneAnalyzer(Scanner scanner) {
    this.errorProneScanner = scanner;
  }

  @Override
  public void started(TaskEvent taskEvent) {
    checkState(initialized);
  }

  @Override
  public void finished(TaskEvent taskEvent) {
    if (taskEvent.getKind() == Kind.ANALYZE) {
      // One TaskEvent is created per class declaration after FLOW is finished, but the TaskEvent
      // only provides the CompilationUnitTree and the symbol (element) for the class declaration.
      // We have to search for the class decl manually...
      // TODO(user): suggest to upstream that TaskEvents provide a TreePath?
      JCClassDecl currentClassTree = null;
      for (Tree declTree : taskEvent.getCompilationUnit().getTypeDecls()) {
        if (declTree instanceof JCClassDecl) {
          JCClassDecl classTree = (JCClassDecl) declTree;
          if (Objects.equal(classTree.sym, taskEvent.getTypeElement())) {
            currentClassTree = classTree;
          }
        }
      }

      TreePath path = currentClassTree != null
          ? TreePath.getPath(taskEvent.getCompilationUnit(), currentClassTree)
          : new TreePath(taskEvent.getCompilationUnit());
      reportReadyForAnalysis(taskEvent, path, compiler.errorCount() > 0);
    }
  }

  /**
   * Returns true if all declarations inside the given compilation unit have been visited.
   */
  private boolean finishedCompilation(CompilationUnitTree tree) {
    for (Tree type : tree.getTypeDecls()) {
      if (!seen.contains(type)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Reports that a class is ready for error-prone to analyze.
   *
   * @param path the path from the compilation unit to the class declaration
   * @param hasErrors true if errors have been reported during the compilation
   */
  public void reportReadyForAnalysis(TaskEvent taskEvent, TreePath path, boolean hasErrors) {
    try {
      // Assert that the event is unique and scan the current tree.
      verify(seen.add(path.getLeaf()), "Duplicate FLOW event for: %s", taskEvent.getTypeElement());
      errorProneScanner.scan(path, createVisitorState(path.getCompilationUnit()));

      // We only get TaskEvents for compilation units if they contain no package declarations
      // (e.g. package-info.java files). Otherwise there are events for each individual
      // declaration. Once we've processed all of the declarations we manually start a post-order
      // visit of the compilation unit.
      if (path.getLeaf().getKind() != Tree.Kind.COMPILATION_UNIT
          && finishedCompilation(path.getCompilationUnit())) {
        CompilationUnitTree tree = path.getCompilationUnit();
        VisitorState visitorState = createVisitorState(path.getCompilationUnit());


        errorProneScanner.matchCompilationUnit(tree, visitorState);

        // Manually traverse into the components of the compilation tree we are interested in, and
        // skip type decls: top-level declarations are visited separately first, and at this point
        // parts of the classes could be lowered away.
        if (tree.getPackage() != null) {
          errorProneScanner.scan(new TreePath(path, tree.getPackage()), visitorState);
        }
        for (ImportTree importTree : tree.getImports()) {
          if (importTree == null) {
            continue;
          }
          errorProneScanner.scan(new TreePath(path, importTree), visitorState);
        }
      }

    } catch (CompletionFailure e) {
      // A CompletionFailure can be triggered when error-prone tries to complete a symbol
      // that isn't on the compilation classpath. This can occur when a check performs an
      // instanceof test on a symbol, which requires inspecting the transitive closure of the
      // symbol's supertypes. If javac didn't need to check the symbol's assignability
      // then a normal compilation would have succeeded, and no diagnostics will have been
      // reported yet, but we don't want to crash javac.
      log.error(
          "proc.cant.access", e.sym, e.getDetailValue(), Throwables.getStackTraceAsString(e));
    } catch (RuntimeException e) {
      // If there is a RuntimeException in an analyzer, swallow it if there are other compiler
      // errors.  This prevents javac from exiting with code 4, Abnormal Termination.
      if (!hasErrors) {
        throw e;
      }
    }
  }

  /**
   * Create a VisitorState object from a compilation unit.
   */
  private VisitorState createVisitorState(CompilationUnitTree compilation) {
    DescriptionListener logReporter = new JavacErrorDescriptionListener(
        log,
        ((JCCompilationUnit) compilation).endPositions,
        compilation.getSourceFile());
    return new VisitorState(context, logReporter, errorProneScanner.severityMap());
  }
}
