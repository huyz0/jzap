package io.github.huyz0.jzap.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

final class OverrideTransformer implements ClassFileTransformer {

    @Override
    public byte[] transform(ClassLoader loader, String internalName, Class<?> beingRedefined,
                            ProtectionDomain domain, byte[] classfileBuffer) {
        if (internalName == null) {
            return null;
        }
        if (beingRedefined == null) {
            ClassOverrides.rememberOriginal(internalName, classfileBuffer);
        }
        return ClassOverrides.lookup(internalName);
    }
}
