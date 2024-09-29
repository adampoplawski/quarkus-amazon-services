package io.quarkus.amazon.dynamodb.enhanced.deployment;

import static io.quarkus.amazon.common.deployment.ClientDeploymentUtil.getNamedClientInjection;
import static io.quarkus.amazon.common.deployment.ClientDeploymentUtil.injectionPointAnnotationsClient;
import static io.quarkus.amazon.common.deployment.ClientDeploymentUtil.namedClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.ClassType;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import io.quarkus.amazon.common.deployment.AmazonClientAsyncResultBuildItem;
import io.quarkus.amazon.common.deployment.AmazonClientSyncResultBuildItem;
import io.quarkus.amazon.common.deployment.RequireAmazonClientInjectionBuildItem;
import io.quarkus.amazon.dynamodb.enhanced.runtime.BeanTableSchemaSubstitutionImplementation;
import io.quarkus.amazon.dynamodb.enhanced.runtime.DynamoDbEnhancedBuildTimeConfig;
import io.quarkus.amazon.dynamodb.enhanced.runtime.DynamodbEnhancedClientRecorder;
import io.quarkus.arc.deployment.BeanRegistrationPhaseBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.processor.InjectionPointInfo;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.BytecodeTransformerBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.pkg.steps.NativeBuild;
import io.quarkus.gizmo.Gizmo;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.configuration.ConfigurationException;
import software.amazon.awssdk.enhanced.dynamodb.DefaultAttributeConverterProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClientExtension;
import software.amazon.awssdk.enhanced.dynamodb.internal.extensions.AutoGeneratedTimestampRecordAttributeTags;
import software.amazon.awssdk.enhanced.dynamodb.internal.extensions.VersionRecordAttributeTags;
import software.amazon.awssdk.enhanced.dynamodb.internal.mapper.BeanAttributeGetter;
import software.amazon.awssdk.enhanced.dynamodb.internal.mapper.BeanAttributeSetter;
import software.amazon.awssdk.enhanced.dynamodb.internal.mapper.BeanTableSchemaAttributeTags;
import software.amazon.awssdk.enhanced.dynamodb.internal.mapper.ObjectConstructor;
import software.amazon.awssdk.enhanced.dynamodb.internal.mapper.ObjectGetterMethod;
import software.amazon.awssdk.enhanced.dynamodb.internal.mapper.StaticGetterMethod;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public class DynamodbEnhancedProcessor {
    private static final String FEATURE = "amazon-dynamodb-enhanced";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    void setup(CombinedIndexBuildItem combinedIndexBuildItem,
            DynamoDbEnhancedBuildTimeConfig buildTimeConfig,
            BeanRegistrationPhaseBuildItem beanRegistrationPhase,
            BuildProducer<RequireAmazonClientInjectionBuildItem> requireClientInjectionProducer) {

        // Discover all known dynamodb-enhanced-client-extension implementors
        List<String> knownDynamodbEnhancedClientExtensionImpls = combinedIndexBuildItem.getIndex()
                .getAllKnownImplementors(DotNames.DYNAMODB_ENHANCED_CLIENT_EXTENSION_NAME)
                .stream()
                .map(c -> c.name().toString()).collect(Collectors.toList());

        // Validate configurations
        Optional<List<String>> extensions = buildTimeConfig.clientExtensions();
        if (extensions != null && extensions.isPresent()) {
            for (var extension : extensions.get()) {
                if (!knownDynamodbEnhancedClientExtensionImpls.contains(extension)) {
                    throw new ConfigurationException(
                            "quarkus.dynamodbenhanced.client-extensions - must list only existing implementations of software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClientExtension");
                }
            }
        }

        // Discover all clients injections in order to determine if async or sync client
        // is required
        for (InjectionPointInfo injectionPoint : beanRegistrationPhase.getInjectionPoints()) {

            org.jboss.jandex.Type injectedType = injectionPoint.getRequiredType();

            if (DotNames.DYNAMODB_ENHANCED_CLIENT.equals(injectedType.name())) {
                requireClientInjectionProducer.produce(new RequireAmazonClientInjectionBuildItem(
                        DotNames.DYNAMODB_CLIENT, getNamedClientInjection(injectionPoint)));
            }
            if (DotNames.DYNAMODB_ENHANCED_ASYNC_CLIENT.equals(injectedType.name())) {
                requireClientInjectionProducer.produce(new RequireAmazonClientInjectionBuildItem(
                        DotNames.DYNAMODB_ASYNC_CLIENT, getNamedClientInjection(injectionPoint)));
            }
        }
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void createClientBuilders(
            DynamodbEnhancedClientRecorder recorder,
            BuildProducer<SyntheticBeanBuildItem> syntheticBean,
            List<AmazonClientSyncResultBuildItem> syncBuilder,
            List<AmazonClientAsyncResultBuildItem> asyncBuilder) {

        String configName = "dynamodb";

        // we cannot filter by requirement origin, so we may create enhanced builder for standard client requirements
        List<AmazonClientSyncResultBuildItem> syncClientRuntime = syncBuilder.stream()
                .filter(c -> configName.equals(c.getAwsClientName()))
                .collect(Collectors.toList());
        List<AmazonClientAsyncResultBuildItem> asyncClientRuntime = asyncBuilder.stream()
                .filter(c -> configName.equals(c.getAwsClientName()))
                .collect(Collectors.toList());

        if (!syncClientRuntime.isEmpty() || !asyncClientRuntime.isEmpty()) {
            RuntimeValue<DynamoDbEnhancedClientExtension> extensions = recorder.createExtensionList();

            for (AmazonClientSyncResultBuildItem amazonClientSyncResultBuildItem : syncClientRuntime) {

                syntheticBean.produce(namedClient(SyntheticBeanBuildItem
                        .configure(DynamoDbEnhancedClient.class), amazonClientSyncResultBuildItem.getClientName())
                        .unremovable()
                        .defaultBean()
                        .scope(ApplicationScoped.class)
                        .setRuntimeInit()
                        .createWith(recorder.createDynamoDbEnhancedClient(extensions,
                                amazonClientSyncResultBuildItem.getClientName()))
                        .addInjectionPoint(ClassType.create(DynamoDbClient.class),
                                injectionPointAnnotationsClient(amazonClientSyncResultBuildItem.getClientName()))
                        .done());
            }

            for (AmazonClientAsyncResultBuildItem amazonClientAsyncResultBuildItem : asyncClientRuntime) {
                syntheticBean.produce(namedClient(SyntheticBeanBuildItem
                        .configure(DynamoDbEnhancedAsyncClient.class), amazonClientAsyncResultBuildItem.getClientName())
                        .unremovable()
                        .defaultBean()
                        .scope(ApplicationScoped.class)
                        .setRuntimeInit()
                        .createWith(recorder.createDynamoDbEnhancedAsyncClient(extensions,
                                amazonClientAsyncResultBuildItem.getClientName()))
                        .addInjectionPoint(ClassType.create(DynamoDbAsyncClient.class),
                                injectionPointAnnotationsClient(amazonClientAsyncResultBuildItem.getClientName()))
                        .done());
            }
        }
    }

    @BuildStep
    public void discoverDynamoDbBeans(CombinedIndexBuildItem combinedIndexBuildItem,
            BuildProducer<DynamodbEnhancedBeanBuildItem> dynamodbEnhancedBeanBuildItems) {
        IndexView index = combinedIndexBuildItem.getIndex();

        // Discover all DynamoDbBean annotated classes and register them
        for (AnnotationInstance annotationInstance : index.getAnnotations(DotNames.DYNAMODB_ENHANCED_BEAN)) {
            ClassInfo beanClassInfo = annotationInstance.target().asClass();
            dynamodbEnhancedBeanBuildItems.produce(new DynamodbEnhancedBeanBuildItem(beanClassInfo.name()));
        }

        // Discover all DynamoDbImmutable annotated classes and register them
        for (AnnotationInstance annotationInstance : index.getAnnotations(DotNames.DYNAMODB_ENHANCED_IMMUTABLE)) {
            var beanClassInfo = annotationInstance.target().asClass();
            dynamodbEnhancedBeanBuildItems.produce(new DynamodbEnhancedBeanBuildItem(beanClassInfo.name()));
        }
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    public void recordTableSchema(
            DynamoDbEnhancedBuildTimeConfig config,
            DynamodbEnhancedClientRecorder recorder,
            List<DynamodbEnhancedBeanBuildItem> dynamodbEnhancedBeanBuildItems,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClass) {

        if (!config.createTableSchemas())
            return;

        List<Class<?>> tableSchemaClasses = new ArrayList<>();
        for (DynamodbEnhancedBeanBuildItem dynamodbEnhancedBeanBuildItem : dynamodbEnhancedBeanBuildItems) {
            try {
                tableSchemaClasses.add(Class.forName(dynamodbEnhancedBeanBuildItem.getClassName().toString(), false,
                        Thread.currentThread().getContextClassLoader()));
            } catch (ClassNotFoundException e) {
            }
        }

        recorder.createTableSchema(tableSchemaClasses);
    }

    @BuildStep(onlyIf = NativeBuild.class)
    public void registerClassesForReflectiveAccess(
            List<DynamodbEnhancedBeanBuildItem> dynamodbEnhancedBeanBuildItems,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClass) {

        for (DynamodbEnhancedBeanBuildItem dynamodbEnhancedBeanBuildItem : dynamodbEnhancedBeanBuildItems) {
            registerInstance(reflectiveClass, dynamodbEnhancedBeanBuildItem.getClassName());
        }

        // Register classes which are used by BeanTableSchema but are not found by the
        // classloader
        reflectiveClass
                .produce(ReflectiveClassBuildItem
                        .builder(DefaultAttributeConverterProvider.class, BeanTableSchemaAttributeTags.class,
                                AutoGeneratedTimestampRecordAttributeTags.class, VersionRecordAttributeTags.class)
                        .methods().build());
    }

    private void registerInstance(BuildProducer<ReflectiveClassBuildItem> reflectiveClass,
            DotName className) {
        reflectiveClass.produce(
                ReflectiveClassBuildItem.builder(className.toString()).methods().build());
    }

    @BuildStep
    private void applyClassTransformation(BuildProducer<BytecodeTransformerBuildItem> transformers) {
        // We rewrite the bytecode to avoid native-image issues (runtime generated
        // lambdas not supported)
        // and class loader issues (that are only problematic in test and dev mode).
        transformers.produce(
                new BytecodeTransformerBuildItem(
                        ObjectGetterMethod.class.getName(),
                        new LambdaToMethodBridgeBuilderCreatorCreateMethodCallRedirectionVisitor(
                                ObjectGetterMethod.class.getSimpleName(), 2)));
        transformers.produce(
                new BytecodeTransformerBuildItem(
                        BeanAttributeGetter.class.getName(),
                        new LambdaToMethodBridgeBuilderCreatorCreateMethodCallRedirectionVisitor(
                                BeanAttributeGetter.class.getSimpleName(), 2)));
        transformers.produce(
                new BytecodeTransformerBuildItem(
                        BeanAttributeSetter.class.getName(),
                        new LambdaToMethodBridgeBuilderCreatorCreateMethodCallRedirectionVisitor(
                                BeanAttributeSetter.class.getSimpleName(), 2)));
        transformers.produce(
                new BytecodeTransformerBuildItem(
                        ObjectConstructor.class.getName(),
                        new LambdaToMethodBridgeBuilderCreatorCreateMethodCallRedirectionVisitor(
                                ObjectConstructor.class.getSimpleName(), 2)));
        transformers.produce(
                new BytecodeTransformerBuildItem(
                        StaticGetterMethod.class.getName(),
                        new LambdaToMethodBridgeBuilderCreatorCreateMethodCallRedirectionVisitor(
                                StaticGetterMethod.class.getSimpleName(), 1)));
    }

    private static class LambdaToMethodBridgeBuilderCreatorCreateMethodCallRedirectionVisitor
            implements BiFunction<String, ClassVisitor, ClassVisitor> {

        public static final String TARGET_METHOD_OWNER = BeanTableSchemaSubstitutionImplementation.class.getName()
                .replace('.',
                        '/');

        private String creatorName;
        private int numArgs;

        public LambdaToMethodBridgeBuilderCreatorCreateMethodCallRedirectionVisitor(String creatorName, int numArgs) {
            this.creatorName = creatorName;
            this.numArgs = numArgs;
        }

        @Override
        public ClassVisitor apply(String className, ClassVisitor outputClassVisitor) {
            return new ClassVisitor(Gizmo.ASM_API_VERSION, outputClassVisitor) {

                @Override
                public MethodVisitor visitMethod(
                        int access, String name, String descriptor, String signature, String[] exceptions) {
                    // https://stackoverflow.com/questions/45180625/how-to-remove-method-body-at-runtime-with-asm-5-2
                    MethodVisitor originalMethodVisitor = super.visitMethod(access, name, descriptor, signature,
                            exceptions);

                    if (name.equals("create")) {
                        return new ReplaceMethodBody(
                                originalMethodVisitor,
                                getMaxLocals(descriptor),
                                visitor -> {
                                    visitor.visitCode();
                                    for (int i = 0; i < numArgs; i++) {
                                        visitor.visitVarInsn(Opcodes.ALOAD, i);
                                    }
                                    Type type = Type.getType(descriptor);
                                    visitor.visitMethodInsn(
                                            Opcodes.INVOKESTATIC, TARGET_METHOD_OWNER, creatorName + "_" + name,
                                            type.getDescriptor(),
                                            false);
                                    visitor.visitInsn(Opcodes.ARETURN);
                                });
                    } else {
                        return originalMethodVisitor;
                    }
                }

                private int getMaxLocals(String descriptor) {
                    return (Type.getArgumentsAndReturnSizes(descriptor) >> 2) - 1;
                }
            };
        }
    }

    private static class ReplaceMethodBody extends MethodVisitor {
        private final MethodVisitor targetWriter;
        private final int newMaxLocals;
        private final Consumer<MethodVisitor> code;

        public ReplaceMethodBody(
                MethodVisitor writer, int newMaxL, Consumer<MethodVisitor> methodCode) {
            super(Opcodes.ASM5);
            this.targetWriter = writer;
            this.newMaxLocals = newMaxL;
            this.code = methodCode;
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            targetWriter.visitMaxs(0, newMaxLocals);
        }

        @Override
        public void visitCode() {
            code.accept(targetWriter);
        }

        @Override
        public void visitEnd() {
            targetWriter.visitEnd();
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            return targetWriter.visitAnnotation(desc, visible);
        }

        @Override
        public void visitParameter(String name, int access) {
            targetWriter.visitParameter(name, access);
        }
    }
}
