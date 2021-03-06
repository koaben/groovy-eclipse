/*
 * Copyright 2003-2010 the original author or authors.
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
package org.codehaus.groovy.classgen;

import groovyjarjarasm.asm.Opcodes;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ConstructorNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.InnerClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.VariableScope;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.FieldExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;

public class InnerClassVisitor extends InnerClassVisitorHelper implements Opcodes {

    private final SourceUnit sourceUnit;
    private ClassNode classNode;
    private static final int PUBLIC_SYNTHETIC = Opcodes.ACC_PUBLIC + Opcodes.ACC_SYNTHETIC;
    private FieldNode thisField = null;
    private MethodNode currentMethod;
    private FieldNode currentField;
    private boolean processingObjInitStatements = false;

    public InnerClassVisitor(CompilationUnit cu, SourceUnit su) {
        sourceUnit = su;
    }

    protected SourceUnit getSourceUnit() {
        return sourceUnit;
    }

    @Override
    public void visitClass(ClassNode node) {
        this.classNode = node;
        thisField = null;
        InnerClassNode innerClass = null;
        if (!node.isEnum() && !node.isInterface() &&
                node instanceof InnerClassNode) {
            innerClass = (InnerClassNode) node;
            if (!isStatic(innerClass) && innerClass.getVariableScope() == null) {
                thisField = innerClass.addField("this$0", PUBLIC_SYNTHETIC, node.getOuterClass(), null);
            }

            if (innerClass.getVariableScope() == null &&
                    innerClass.getDeclaredConstructors().isEmpty()) {
                // add dummy constructor
                innerClass.addConstructor(ACC_PUBLIC, new Parameter[0], null, null);
            }
        }

        super.visitClass(node);

        if (node.isEnum() || node.isInterface()) return;
        addDispatcherMethods();
        if (innerClass == null) return;

        if (node.getSuperClass().isInterface()) {
            node.addInterface(node.getUnresolvedSuperClass());
            node.setUnresolvedSuperClass(ClassHelper.OBJECT_TYPE);
        }
    }

    @Override
    protected void visitObjectInitializerStatements(ClassNode node) {
        processingObjInitStatements = true;
        super.visitObjectInitializerStatements(node);
        processingObjInitStatements = false;
    }

    @Override
    public void visitConstructor(ConstructorNode node) {
        addThisReference(node);
        super.visitConstructor(node);
    }

    private boolean shouldHandleImplicitThisForInnerClass(ClassNode cn) {
        if (cn.isEnum() || cn.isInterface()) return false;
        if ((cn.getModifiers() & Opcodes.ACC_STATIC) != 0) return false;

        if (!(cn instanceof InnerClassNode)) return false;
        InnerClassNode innerClass = (InnerClassNode) cn;
        // scope != null means aic, we don't handle that here
        if (innerClass.getVariableScope() != null) return false;
        // static inner classes don't need this$0
        if ((innerClass.getModifiers() & ACC_STATIC) != 0) return false;

        return true;
    }

    private void addThisReference(ConstructorNode node) {
        if (!shouldHandleImplicitThisForInnerClass(classNode)) return;
        Statement code = node.getCode();

        // add "this$0" field init

        //add this parameter to node
        Parameter[] params = node.getParameters();
        Parameter[] newParams = new Parameter[params.length + 1];
        System.arraycopy(params, 0, newParams, 1, params.length);
        Parameter thisPara = new Parameter(classNode.getOuterClass(), getUniqueName(params, node));
        newParams[0] = thisPara;
        node.setParameters(newParams);

        BlockStatement block = null;
        if (code == null) {
            block = new BlockStatement();
        } else if (!(code instanceof BlockStatement)) {
            block = new BlockStatement();
            block.addStatement(code);
        } else {
            block = (BlockStatement) code;
        }
        BlockStatement newCode = new BlockStatement();
        addFieldInit(thisPara, thisField, newCode);
        ConstructorCallExpression cce = getFirstIfSpecialConstructorCall(block);
        if (cce == null) {
            cce = new ConstructorCallExpression(ClassNode.SUPER, new TupleExpression());
            block.getStatements().add(0, new ExpressionStatement(cce));
        }
        if (shouldImplicitlyPassThisPara(cce)) {
            // add thisPara to this(...)
            TupleExpression args = (TupleExpression) cce.getArguments();
            List<Expression> expressions = args.getExpressions();
            VariableExpression ve = new VariableExpression(thisPara.getName());
            ve.setAccessedVariable(thisPara);
            expressions.add(0, ve);
        }
        if (cce.isSuperCall()) {
            // we have a call to super here, so we need to add
            // our code after that
            block.getStatements().add(1, newCode);
        }
        node.setCode(block);
    }

    private boolean shouldImplicitlyPassThisPara(ConstructorCallExpression cce) {
        boolean pass = false;
        ClassNode superCN = classNode.getSuperClass();
        if (cce.isThisCall()) {
            pass = true;
        } else if (cce.isSuperCall()) {
            // if the super class is another non-static inner class in the same outer class, implicit this
            // needs to be passed
            if (!superCN.isEnum() && !superCN.isInterface() && superCN instanceof InnerClassNode) {
                InnerClassNode superInnerCN = (InnerClassNode) superCN;
                if (!isStatic(superInnerCN) && superCN.getOuterClass().equals(classNode.getOuterClass())) {
                    pass = true;
                }
            }
        }
        return pass;
    }

    private String getUniqueName(Parameter[] params, ConstructorNode node) {
        String namePrefix = "$p";
        outer:
        for (int i = 0; i < 100; i++) {
            namePrefix = namePrefix + "$";
            for (Parameter p : params) {
                if (p.getName().equals(namePrefix)) continue outer;
            }
            return namePrefix;
        }
        addError("unable to find a unique prefix name for synthetic this reference", node);
        return namePrefix;
    }

    private ConstructorCallExpression getFirstIfSpecialConstructorCall(BlockStatement code) {
        if (code == null) return null;

        final List<Statement> statementList = code.getStatements();
        if (statementList.isEmpty()) return null;

        final Statement statement = statementList.get(0);
        if (!(statement instanceof ExpressionStatement)) return null;

        Expression expression = ((ExpressionStatement) statement).getExpression();
        if (!(expression instanceof ConstructorCallExpression)) return null;
        ConstructorCallExpression cce = (ConstructorCallExpression) expression;
        if (cce.isSpecialCall()) return cce;
        return null;
    }

    @Override
    protected void visitConstructorOrMethod(MethodNode node, boolean isConstructor) {
        this.currentMethod = node;
        super.visitConstructorOrMethod(node, isConstructor);
        this.currentMethod = null;
    }

    @Override
    public void visitField(FieldNode node) {
        this.currentField = node;
        super.visitField(node);
        this.currentField = null;
    }

    @Override
    public void visitProperty(PropertyNode node) {
        final FieldNode field = node.getField();
        final Expression init = field.getInitialExpression();
        field.setInitialValueExpression(null);
        super.visitProperty(node);
        field.setInitialValueExpression(init);
    }

    @Override
    public void visitConstructorCallExpression(ConstructorCallExpression call) {
        super.visitConstructorCallExpression(call);
        if (!call.isUsingAnonymousInnerClass()) {
            passThisReference(call);
            return;
        }

        InnerClassNode innerClass = (InnerClassNode) call.getType();
        if (!innerClass.getDeclaredConstructors().isEmpty()) return;
        if ((innerClass.getModifiers() & ACC_STATIC) != 0) return;

        VariableScope scope = innerClass.getVariableScope();
        if (scope == null) return;


        boolean isStatic = scope.isInStaticContext();
        // expressions = constructor call arguments
        List<Expression> expressions = ((TupleExpression) call.getArguments()).getExpressions();
        // block = init code for the constructor we produce
        BlockStatement block = new BlockStatement();
        // parameters = parameters of the constructor
        final int additionalParamCount = 1 + scope.getReferencedLocalVariablesCount();
        List<Parameter> parameters = new ArrayList<Parameter>(expressions.size() + additionalParamCount);
        // superCallArguments = arguments for the super call == the constructor call arguments
        List<Expression> superCallArguments = new ArrayList<Expression>(expressions.size());

        // first we add a super() call for all expressions given in the
        // constructor call expression
        int pCount = additionalParamCount;
        for (Expression expr : expressions) {
            pCount++;
            // add one parameter for each expression in the
            // constructor call
            Parameter param = new Parameter(ClassHelper.OBJECT_TYPE, "p" + pCount);
            parameters.add(param);
            // add to super call
            superCallArguments.add(new VariableExpression(param));
        }

        // add the super call
        ConstructorCallExpression cce = new ConstructorCallExpression(
                ClassNode.SUPER,
                new TupleExpression(superCallArguments)
        );

        block.addStatement(new ExpressionStatement(cce));

        // we need to add "this" to access unknown methods/properties
        // this is saved in a field named this$0
        pCount = 0;
        expressions.add(pCount, VariableExpression.THIS_EXPRESSION);
        ClassNode outerClassType = getClassNode(innerClass.getOuterClass(), isStatic);
        Parameter thisParameter = new Parameter(outerClassType, "p" + pCount);
        parameters.add(pCount, thisParameter);

        thisField = innerClass.addField("this$0", PUBLIC_SYNTHETIC, outerClassType, null);
        addFieldInit(thisParameter, thisField, block);

        // for each shared variable we add a reference and save it as field
        for (Iterator it = scope.getReferencedLocalVariablesIterator(); it.hasNext();) {
            pCount++;
            org.codehaus.groovy.ast.Variable var = (org.codehaus.groovy.ast.Variable) it.next();
            VariableExpression ve = new VariableExpression(var);
            ve.setClosureSharedVariable(true);
            ve.setUseReferenceDirectly(true);
            expressions.add(pCount, ve);

            // GRECLIPSE - use plain ref
            ClassNode rawReferenceType = ClassHelper.REFERENCE_TYPE.getPlainNodeReference();
            Parameter p = new Parameter(rawReferenceType, "p" + pCount);
            parameters.add(pCount, p);
            final VariableExpression initial = new VariableExpression(p);
            initial.setUseReferenceDirectly(true);
            final FieldNode pField = innerClass.addFieldFirst(ve.getName(), PUBLIC_SYNTHETIC,rawReferenceType, initial);
            pField.setHolder(true);
        }

        innerClass.addConstructor(ACC_SYNTHETIC, parameters.toArray(new Parameter[0]), ClassNode.EMPTY_ARRAY, block);
    }

    // this is the counterpart of addThisReference(). To non-static inner classes, outer this should be
    // passed as the first argument implicitly.
    private void passThisReference(ConstructorCallExpression call) {
        ClassNode cn = call.getType().redirect();
        if (!shouldHandleImplicitThisForInnerClass(cn)) return;

        boolean isInStaticContext = true;
        if (currentMethod != null)
            isInStaticContext = currentMethod.getVariableScope().isInStaticContext();
        else if (currentField != null)
            isInStaticContext = currentField.isStatic();
        else if (processingObjInitStatements)
            isInStaticContext = false;

        // if constructor call is not in static context, return
        if (isInStaticContext) {
            // constructor call is in static context and the inner class is non-static - 1st arg is supposed to be
            // passed as enclosing "this" instance
            //
            Expression args = call.getArguments();
            if (args instanceof TupleExpression && ((TupleExpression) args).getExpressions().isEmpty()) {
                addError("No enclosing instance passed in constructor call of a non-static inner class", call);
            }
            return;
        }

        // calculate outer class which we need for this$0
        ClassNode parent = classNode;
        int level = 0;
        for (; parent != null && parent != cn.getOuterClass(); parent = parent.getOuterClass()) {
            level++;
        }

        // if constructor call is not in outer class, don't pass 'this' implicitly. Return.
        if (parent == null) return;

        //add this parameter to node
        Expression argsExp = call.getArguments();
        if (argsExp instanceof TupleExpression) {
            TupleExpression argsListExp = (TupleExpression) argsExp;
            Expression this0 = VariableExpression.THIS_EXPRESSION;
            for (int i = 0; i != level; ++i)
                this0 = new PropertyExpression(this0, "this$0");
            argsListExp.getExpressions().add(0, this0);
        }
    }

    private void addDispatcherMethods() {
        final int objectDistance = getObjectDistance(classNode);

        // since we added an anonymous inner class we should also
        // add the dispatcher methods

        // add method dispatcher
        Parameter[] parameters = new Parameter[]{
                new Parameter(ClassHelper.STRING_TYPE, "name"),
                new Parameter(ClassHelper.OBJECT_TYPE, "args")
        };
        MethodNode method = classNode.addSyntheticMethod(
                "this$dist$invoke$" + objectDistance,
                ACC_PUBLIC + ACC_SYNTHETIC,
                ClassHelper.OBJECT_TYPE,
                parameters,
                ClassNode.EMPTY_ARRAY,
                null
        );

        BlockStatement block = new BlockStatement();
        setMethodDispatcherCode(block, VariableExpression.THIS_EXPRESSION, parameters);
        method.setCode(block);

        // add property setter
        parameters = new Parameter[]{
                new Parameter(ClassHelper.STRING_TYPE, "name"),
                new Parameter(ClassHelper.OBJECT_TYPE, "value")
        };
        method = classNode.addSyntheticMethod(
                "this$dist$set$" + objectDistance,
                ACC_PUBLIC + ACC_SYNTHETIC,
                ClassHelper.VOID_TYPE,
                parameters,
                ClassNode.EMPTY_ARRAY,
                null
        );

        block = new BlockStatement();
        setPropertySetterDispatcher(block, VariableExpression.THIS_EXPRESSION, parameters);
        method.setCode(block);

        // add property getter
        parameters = new Parameter[]{
                new Parameter(ClassHelper.STRING_TYPE, "name")
        };
        method = classNode.addSyntheticMethod(
                "this$dist$get$" + objectDistance,
                ACC_PUBLIC + ACC_SYNTHETIC,
                ClassHelper.OBJECT_TYPE,
                parameters,
                ClassNode.EMPTY_ARRAY,
                null
        );

        block = new BlockStatement();
        setPropertyGetterDispatcher(block, VariableExpression.THIS_EXPRESSION, parameters);
        method.setCode(block);
    }

    private static void addFieldInit(Parameter p, FieldNode fn, BlockStatement block) {
        VariableExpression ve = new VariableExpression(p);
        FieldExpression fe = new FieldExpression(fn);
        block.addStatement(new ExpressionStatement(
                new BinaryExpression(
                        fe,
                        Token.newSymbol(Types.ASSIGN, -1, -1),
                        ve
                )
        ));
    }
}
