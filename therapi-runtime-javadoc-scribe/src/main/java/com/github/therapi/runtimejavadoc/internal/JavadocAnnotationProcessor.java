package com.github.therapi.runtimejavadoc.internal;

import com.eclipsesource.json.JsonObject;
import com.github.therapi.runtimejavadoc.RetainJavadoc;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static com.github.therapi.runtimejavadoc.internal.RuntimeJavadocHelper.javadocResourceSuffix;
import static java.nio.charset.StandardCharsets.UTF_8;

public class JavadocAnnotationProcessor extends AbstractProcessor {

  private static final String PACKAGES_OPTION = "javadoc.packages";

  private static final Predicate<Element> ALL_PACKAGES = e -> true;

  private JsonJavadocBuilder jsonJavadocBuilder;

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
    this.jsonJavadocBuilder = new JsonJavadocBuilder(processingEnv);

    final Map<String, String> options = processingEnv.getOptions();
    final String packagesOption = options.get(PACKAGES_OPTION);

    // Retain Javadoc for classes that match this predicate
    final Predicate<Element> packageFilter =
        packagesOption == null ? ALL_PACKAGES : new PackageFilter(packagesOption);

    // Make sure each element only gets processed once.
    final Set<Element> alreadyProcessed = new HashSet<>();

    // If retaining Javadoc for all packages, the @RetainJavadoc annotation is redundant.
    // Otherwise, make sure annotated classes have their Javadoc retained regardless of package.
    if (packageFilter != ALL_PACKAGES) {
      for (TypeElement annotation : annotations) {
        if (isRetainJavadocAnnotation(annotation)) {
          for (Element e : roundEnvironment.getElementsAnnotatedWith(annotation)) {
            generateJavadoc(e, alreadyProcessed);
          }
        }
      }
    }

    for (Element e : roundEnvironment.getRootElements()) {
      if (packageFilter.test(e)) {
        generateJavadoc(e, alreadyProcessed);
      }
    }

    return false;
  }

  private static boolean isRetainJavadocAnnotation(TypeElement annotation) {
    return annotation.getQualifiedName().toString().equals(RetainJavadoc.class.getName())
        || annotation.getAnnotation(RetainJavadoc.class) != null;
  }

  private void generateJavadoc(Element element, Set<Element> alreadyProcessed) {
    ElementKind kind = element.getKind();
    if (kind == ElementKind.CLASS || kind == ElementKind.INTERFACE || kind == ElementKind.ENUM) {
      try {
        generateJavadocForClass(element, alreadyProcessed);
      } catch (Exception ex) {
        processingEnv.getMessager()
            .printMessage(Diagnostic.Kind.ERROR, "Javadoc retention failed; " + ex, element);
        throw new RuntimeException("Javadoc retention failed for " + element, ex);
      }
    }

    for (Element enclosed : element.getEnclosedElements()) {
      generateJavadoc(enclosed, alreadyProcessed);
    }
  }

  private void generateJavadocForClass(Element element, Set<Element> alreadyProcessed) throws IOException {
    if (!alreadyProcessed.add(element)) {
      return;
    }
    TypeElement classElement = (TypeElement) element;
    Optional<JsonObject> maybeClassJsonDoc = jsonJavadocBuilder.getClassJavadocAsJson(classElement);
    if (maybeClassJsonDoc.isPresent()) {
      JsonObject classJsonDoc = maybeClassJsonDoc.get();
      outputJsonDoc(classElement, classJsonDoc);
    }
  }

  private void outputJsonDoc(TypeElement classElement, JsonObject classJsonDoc) throws IOException {
    String jsonString = classJsonDoc.toString();
    FileObject resource = createJavadocResourceFile(classElement);
    try (OutputStream os = resource.openOutputStream()) {
      os.write(jsonString.getBytes(UTF_8));
    }
  }

  private FileObject createJavadocResourceFile(TypeElement classElement) throws IOException {
    PackageElement packageElement = getPackageElement(classElement);
    String packageName = packageElement.getQualifiedName().toString();
    String relativeName = getClassName(classElement) + javadocResourceSuffix();
    return processingEnv.getFiler()
        .createResource(StandardLocation.CLASS_OUTPUT, packageName, relativeName, classElement);
  }

  private static PackageElement getPackageElement(Element element) {
    if (element instanceof PackageElement) {
      return (PackageElement) element;
    }
    return getPackageElement(element.getEnclosingElement());
  }

  private static String getClassName(TypeElement typeElement) {
    // we can't take the simple name if we want to return names like EnclosingClass$NestedClass
    String typeName = typeElement.getQualifiedName().toString();
    String packageName = getPackageElement(typeElement).getQualifiedName().toString();

    if (!packageName.isEmpty()) {
      typeName = typeName.substring(packageName.length() + 1);
      typeName = typeName.replace(".", "$");
    }
    return typeName;
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return Collections.singleton("*");
  }

  @Override
  public Set<String> getSupportedOptions() {
    return Collections.singleton(PACKAGES_OPTION);
  }
}
