/*
 * Copyright 2015 Google Inc. All Rights Reserved.
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

package com.google.errorprone.matchers;

import com.google.errorprone.VisitorState;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.tree.JCTree;

/**
 * @author sebastian.h.monte@gmail.com (Sebastian Monte)
 */
public class AnnotationSimpleName implements Matcher<AnnotationTree> {

  private final String annotationSimpleName;

  public AnnotationSimpleName(String annotationSimpleName) {
    this.annotationSimpleName = annotationSimpleName;
  }

  @Override
  public boolean matches(AnnotationTree annotationTree, VisitorState state) {
    Tree type = annotationTree.getAnnotationType();
    if (type.getKind() == Tree.Kind.IDENTIFIER && type instanceof JCTree.JCIdent) {
      JCTree.JCIdent jcIdent = (JCTree.JCIdent) type;
      return jcIdent.sym.getSimpleName().toString().equals(annotationSimpleName);
    } else if (type instanceof JCTree.JCFieldAccess) {
      JCTree.JCFieldAccess jcFieldAccess = (JCTree.JCFieldAccess) type;
      return jcFieldAccess.sym.getSimpleName().toString().equals(annotationSimpleName);
    } else {
      return false;
    }
  }
}
