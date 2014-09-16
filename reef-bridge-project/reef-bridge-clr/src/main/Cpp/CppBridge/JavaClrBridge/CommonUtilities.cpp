/**
 * Copyright (C) 2014 Microsoft Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "Clr2JavaImpl.h"

namespace Microsoft {
  namespace Reef {
    namespace Driver {
      namespace Bridge {
        ref class ManagedLog {
          internal:
            static BridgeLogger^ LOGGER = BridgeLogger::GetLogger("<C++>CommonUtilities");
        };

        IEvaluatorDescriptor^ CommonUtilities::RetrieveEvaluatorDescriptor(jobject object, JavaVM* jvm) {
          ManagedLog::LOGGER->LogStart("CommonUtilities::GetEvaluatorDescriptor");
          JNIEnv *env = RetrieveEnv(jvm);
          jclass jclassActiveContext = env->GetObjectClass (object);
          jmethodID jmidGetEvaluatorDescriptor = env->GetMethodID(jclassActiveContext, "getEvaluatorDescriptorSring", "()Ljava/lang/String;");

          if (jmidGetEvaluatorDescriptor == NULL) {
            ManagedLog::LOGGER->Log("jmidGetEvaluatorDescriptor is NULL");
            return nullptr;
          }
          jstring jevaluatorDescriptorString = (jstring)env -> CallObjectMethod(
                                                 object,
                                                 jmidGetEvaluatorDescriptor);
          String^ evaluatorDescriptorString = ManagedStringFromJavaString(env, jevaluatorDescriptorString);
          ManagedLog::LOGGER->LogStop("InteropUtil::GetEvaluatorDescriptor");

          return gcnew EvaluatorDescriptorImpl(evaluatorDescriptorString);
        }
      }
    }
  }
}