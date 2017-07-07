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
package com.google.devtools.build.android.desugar;

import com.google.common.collect.ImmutableSet;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Class visitor to collect all the lambda methods that are used in invokedynamic instructions.
 *
 * <p>Note that this class only collects lambda methods. If the invokedynamic is used for other
 * purposes, the methods used in the instruction are NOT collected.
 */
public class InvokeDynamicLambdaMethodCollector extends ClassVisitor {

  private final ImmutableSet.Builder<MethodInfo> lambdaMethodsUsedInInvokeDyanmic =
      ImmutableSet.builder();

  public InvokeDynamicLambdaMethodCollector() {
    super(Opcodes.ASM5);
  }

  @Override
  public MethodVisitor visitMethod(
      int access, String name, String desc, String signature, String[] exceptions) {
    MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
    return new LambdaMethodCollector(mv);
  }

  private class LambdaMethodCollector extends MethodVisitor {

    public LambdaMethodCollector(MethodVisitor dest) {
      super(Opcodes.ASM5, dest);
    }

    @Override
    public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
      if (!"java/lang/invoke/LambdaMetafactory".equals(bsm.getOwner())) {
        // Not an invokedynamic for a lambda expression
        return;
      }
      Handle handle = (Handle) bsmArgs[1];
      lambdaMethodsUsedInInvokeDyanmic.add(
          MethodInfo.create(handle.getOwner(), handle.getName(), handle.getDesc()));
      super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
    }
  }

  public ImmutableSet<MethodInfo> getLambdaMethodsUsedInInvokeDyanmic() {
    return lambdaMethodsUsedInInvokeDyanmic.build();
  }
}
