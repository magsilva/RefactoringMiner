/*
 *
 *  Copyright 2015 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package springfox.documentation.spring.web.plugins;

import com.fasterxml.classmate.TypeResolver;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.Ordering;
import org.springframework.web.bind.annotation.RequestMethod;
import springfox.documentation.PathProvider;
import springfox.documentation.annotations.Incubating;
import springfox.documentation.schema.AlternateTypeRule;
import springfox.documentation.schema.AlternateTypeRules;
import springfox.documentation.schema.CodeGenGenericTypeNamingStrategy;
import springfox.documentation.schema.DefaultGenericTypeNamingStrategy;
import springfox.documentation.schema.WildcardType;
import springfox.documentation.service.ApiDescription;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.ApiListingReference;
import springfox.documentation.service.Operation;
import springfox.documentation.service.ResponseMessage;
import springfox.documentation.service.SecurityScheme;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spi.schema.GenericTypeNamingStrategy;
import springfox.documentation.spi.service.DocumentationPlugin;
import springfox.documentation.spi.service.contexts.ApiSelector;
import springfox.documentation.spi.service.contexts.DocumentationContext;
import springfox.documentation.spi.service.contexts.DocumentationContextBuilder;
import springfox.documentation.spi.service.contexts.SecurityContext;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.collect.FluentIterable.*;
import static com.google.common.collect.Lists.*;
import static com.google.common.collect.Maps.*;
import static com.google.common.collect.Sets.*;
import static org.springframework.util.StringUtils.*;

/**
 * A builder which is intended to be the primary interface into the swagger-springmvc framework.
 * Provides sensible defaults and convenience methods for configuration.
 */
public class Docket implements DocumentationPlugin {

  public static final String DEFAULT_GROUP_NAME = "default";
  private final DocumentationType documentationType;
  private String groupName;
  private ApiInfo apiInfo;
  private PathProvider pathProvider;
  private List<? extends SecurityScheme> securitySchemes;
  private Ordering<ApiListingReference> apiListingReferenceOrdering;
  private Ordering<ApiDescription> apiDescriptionOrdering;
  private Ordering<Operation> operationOrdering;

  private AtomicBoolean initialized = new AtomicBoolean(false);
  private boolean enabled = true;
  private GenericTypeNamingStrategy genericsNamingStrategy = new DefaultGenericTypeNamingStrategy();
  private boolean applyDefaultResponseMessages = true;
  private final List<SecurityContext> securityContexts = newArrayList();
  private final Map<RequestMethod, List<ResponseMessage>> responseMessages = newHashMap();
  private final List<Function<TypeResolver, AlternateTypeRule>> ruleBuilders = newArrayList();
  private final Set<Class> ignorableParameterTypes = newHashSet();
  private final Set<String> protocols = newHashSet();
  private final Set<String> produces = newHashSet();
  private final Set<String> consumes = newHashSet();
  private Optional<String> pathMapping = Optional.absent();
  private ApiSelector apiSelector = ApiSelector.DEFAULT;
  private boolean enableUrlTemplating = false;

  public Docket(DocumentationType documentationType) {
    this.documentationType = documentationType;
  }

  /**
   * Sets the api's meta information as included in the json ResourceListing response.
   *
   * @param apiInfo Indicates the api information
   * @return this Docket
   */
  public Docket apiInfo(ApiInfo apiInfo) {
    this.apiInfo = apiInfo;
    return this;
  }

  /**
   * Configures the global com.wordnik.swagger.model.SecurityScheme's applicable to all or some of the api
   * operations. The configuration of which operations have associated SecuritySchemes is configured with
   * springfox.swagger.plugins.Docket#securityContexts
   *
   * @param securitySchemes a list of security schemes
   * @return this Docket
   */
  public Docket securitySchemes(List<? extends SecurityScheme> securitySchemes) {
    this.securitySchemes = securitySchemes;
    return this;
  }

  /**
   * Configures which api operations (via regex patterns) and HTTP methods to apply security contexts to apis.
   *
   * @param securityContexts - defines security requirements for the apis
   * @return this Docket
   */
  public Docket securityContexts(List<SecurityContext> securityContexts) {
    this.securityContexts.addAll(securityContexts);
    return this;
  }

  /**
   * If more than one instance of Docket exists, each one must have a unique groupName as
   * supplied by this method. Defaults to "default".
   *
   * @param groupName - the unique identifier of this swagger group/configuration
   * @return this Docket
   */
  public Docket groupName(String groupName) {
    this.groupName = groupName;
    return this;
  }

  /**
   * Determines the generated, swagger specific, urls.
   * <p/>
   * By default, relative urls are generated. If absolute urls are required, supply an implementation of
   * AbsoluteSwaggerPathProvider
   *
   * @param pathProvider
   * @return this Docket
   * @see springfox.documentation.spring.web.AbstractPathProvider
   */
  public Docket pathProvider(PathProvider pathProvider) {
    this.pathProvider = pathProvider;
    return this;
  }

  /**
   * Overrides the default http response messages at the http request method level.
   * <p/>
   * To set specific response messages for specific api operations use the swagger core annotations on
   * the appropriate controller methods.
   *
   * @param requestMethod    - http request method for which to apply the message
   * @param responseMessages - the message
   * @return this Docket
   * @see com.wordnik.swagger.annotations.ApiResponse
   * and
   * @see com.wordnik.swagger.annotations.ApiResponses
   * @see springfox.documentation.spi.service.contexts.Defaults#defaultResponseMessages()
   */
  public Docket globalResponseMessage(RequestMethod requestMethod,
                                      List<ResponseMessage> responseMessages) {

    this.responseMessages.put(requestMethod, responseMessages);
    return this;
  }

  /**
   * Adds ignored controller method parameter types so that the framework does not generate swagger model or parameter
   * information for these specific types.
   * e.g. HttpServletRequest/HttpServletResponse which are already included in the pre-configured ignored types.
   *
   * @param classes the classes to ignore
   * @return this Docket
   * @see springfox.documentation.spi.service.contexts.Defaults#defaultIgnorableParameterTypes()
   */
  public Docket ignoredParameterTypes(Class... classes) {
    this.ignorableParameterTypes.addAll(Arrays.asList(classes));
    return this;
  }

  public Docket produces(Set<String> produces) {
    this.produces.addAll(produces);
    return this;
  }

  public Docket consumes(Set<String> consumes) {
    this.consumes.addAll(consumes);
    return this;
  }

  public Docket protocols(Set<String> protocols) {
    this.protocols.addAll(protocols);
    return this;
  }

  /**
   * Adds model substitution rules (alternateTypeRules)
   *
   * @param alternateTypeRules
   * @return this Docket
   * @see AlternateTypeRules#newRule(java.lang.reflect.Type,
   * java.lang.reflect.Type)
   */
  public Docket alternateTypeRules(AlternateTypeRule... alternateTypeRules) {
    this.ruleBuilders.addAll(from(newArrayList(alternateTypeRules)).transform(identityRuleBuilder()).toList());
    return this;
  }

  /**
   * Provide an ordering schema for operations
   *
   * NOTE: @see <a href="https://github.com/springfox/springfox/issues/732">#732</a> in case you're wondering why
   * specifying position might not work.
   * @param operationOrdering
   * @return
   */
  public Docket operationOrdering(Ordering<Operation> operationOrdering) {
    this.operationOrdering = operationOrdering;
    return this;
  }

  private Function<AlternateTypeRule, Function<TypeResolver, AlternateTypeRule>> identityRuleBuilder() {
    return new Function<AlternateTypeRule, Function<TypeResolver, AlternateTypeRule>>() {
      @Override
      public Function<TypeResolver, AlternateTypeRule> apply(AlternateTypeRule rule) {
        return identityFunction(rule);
      }
    };
  }

  private Function<TypeResolver, AlternateTypeRule> identityFunction(final AlternateTypeRule rule) {
    return new Function<TypeResolver, AlternateTypeRule>() {
      @Override
      public AlternateTypeRule apply(TypeResolver typeResolver) {
        return rule;
      }
    };
  }

  /**
   * Directly substitutes a model class with the supplied substitute
   * e.g
   * <code>directModelSubstitute(LocalDate.class, Date.class)</code>
   * would substitute LocalDate with Date
   *
   * @param clazz class to substitute
   * @param with  the class which substitutes 'clazz'
   * @return this Docket
   */
  public Docket directModelSubstitute(final Class clazz, final Class with) {
    this.ruleBuilders.add(newSubstitutionFunction(clazz, with));
    return this;
  }

  /**
   * Substitutes each generic class with it's direct parameterized type. Use this method to
   * only for types with a single parameterized type. e.g. <code>List&lt;T&gt; or ResponseEntity&lt;T&gt;</code>
   * <code>.genericModelSubstitutes(ResponseEntity.class)</code>
   * would substitute ResponseEntity &lt;MyModel&gt; with MyModel
   *
   * @param genericClasses - generic classes on which to apply generic model substitution.
   * @return this Docket
   */
  public Docket genericModelSubstitutes(Class... genericClasses) {
    for (Class clz : genericClasses) {
      this.ruleBuilders.add(newGenericSubstitutionFunction(clz));
    }
    return this;
  }

  /**
   * Allows ignoring predefined response message defaults
   *
   * @param apply flag to determine if the default response messages are used
   *              true   - the default response messages are added to the global response messages
   *              false  - the default response messages are added to the global response messages
   * @return this Docket
   */
  public Docket useDefaultResponseMessages(boolean apply) {
    this.applyDefaultResponseMessages = apply;
    return this;
  }

  /**
   * Controls how ApiListingReference's are sorted.
   * i.e the ordering of the api's within the swagger Resource Listing.
   * The default sort is Lexicographically by the ApiListingReference's path
   *
   * NOTE: @see <a href="https://github.com/springfox/springfox/issues/732">#732</a> in case you're wondering why
   * specifying position might not work.
   *
   * @param apiListingReferenceOrdering
   * @return this Docket
   */
  public Docket apiListingReferenceOrdering(Ordering<ApiListingReference> apiListingReferenceOrdering) {
    this.apiListingReferenceOrdering = apiListingReferenceOrdering;
    return this;
  }

  /**
   * Controls how <code>com.wordnik.swagger.model.ApiDescription</code>'s are ordered.
   * The default sort is Lexicographically by the ApiDescription's path.
   *
   * NOTE: @see <a href="https://github.com/springfox/springfox/issues/732">#732</a> in case you're wondering why
   * specifying position might not work.
   *
   * @param apiDescriptionOrdering
   * @return this Docket
   * @see springfox.documentation.spring.web.scanners.ApiListingScanner
   */
  public Docket apiDescriptionOrdering(Ordering<ApiDescription> apiDescriptionOrdering) {
    this.apiDescriptionOrdering = apiDescriptionOrdering;
    return this;
  }

  /**
   * Hook to externally control auto initialization of this swagger plugin instance.
   * Typically used if defer initialization.
   *
   * @param externallyConfiguredFlag - true to turn it on, false to turn it off
   * @return this Docket
   */
  public Docket enable(boolean externallyConfiguredFlag) {
    this.enabled = externallyConfiguredFlag;
    return this;
  }

  /**
   * Set this to true in order to make the documentation code generation friendly
   *
   * @param forCodeGen - true|false determines the naming strategy used
   * @return this Docket
   */
  public Docket forCodeGeneration(boolean forCodeGen) {
    if (forCodeGen) {
      genericsNamingStrategy = new CodeGenGenericTypeNamingStrategy();
    }
    return this;
  }

  /**
   * Extensibility mechanism to add a servlet path mapping, if there is one, to the apis base path.
   * @param path - path that acts as a prefix to the api base path
   * @return this Docket
   */
  public Docket pathMapping(String path) {
    this.pathMapping = Optional.fromNullable(path);
    return this;
  }


  /**
   * Decides whether to use url templating for paths. This is especially useful when you have search api's that
   * might have multiple request mappings for each search use case.
   *
   * This is an incubating feature that may not continue to be supported after the swagger specification is modified
   * to accomodate the usecase as described in issue #711
   * @param enabled
   * @return
   */
  @Incubating("2.0.3")
  public Docket enableUrlTemplating(boolean enabled) {
    this.enableUrlTemplating = enabled;
    return this;
  }

  Docket selector(ApiSelector apiSelector) {
    this.apiSelector = apiSelector;
    return this;
  }

  /**
   * Initiates a builder for api selection.
   * @return api selection builder. To complete building the api selector, the build method of the api selector
   * needs to be called, this will automatically fall back to building the docket when the build method is called.
   */
  public ApiSelectorBuilder select() {
    return new ApiSelectorBuilder(this);
  }

  /**
   * Builds the Docket by merging/overlaying user specified values.
   * It is not necessary to call this method when defined as a spring bean.
   * NOTE: Calling this method more than once has no effect.
   *
   * @see DocumentationPluginsBootstrapper
   */
  public DocumentationContext configure(DocumentationContextBuilder builder) {
    if (initialized.compareAndSet(false, true)) {
      configureDefaults();
    }
    return builder
            .apiInfo(apiInfo)
            .selector(apiSelector)
            .applyDefaultResponseMessages(applyDefaultResponseMessages)
            .additionalResponseMessages(responseMessages)
            .additionalIgnorableTypes(ignorableParameterTypes)
            .ruleBuilders(ruleBuilders)
            .groupName(groupName)
            .pathProvider(pathProvider)
            .securityContexts(securityContexts)
            .securitySchemes(securitySchemes)
            .apiListingReferenceOrdering(apiListingReferenceOrdering)
            .apiDescriptionOrdering(apiDescriptionOrdering)
            .operationOrdering(operationOrdering)
            .produces(produces)
            .consumes(consumes)
            .protocols(protocols)
            .genericsNaming(genericsNamingStrategy)
            .pathMapping(pathMapping)
            .enableUrlTemplating(enableUrlTemplating)
            .build();
  }

  @Override
  public String getGroupName() {
    return groupName;
  }

  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public DocumentationType getDocumentationType() {
    return documentationType;
  }

  @Override
  public boolean supports(DocumentationType delimiter) {
    return documentationType.equals(delimiter);
  }

  private Function<TypeResolver, AlternateTypeRule> newSubstitutionFunction(final Class clazz, final Class with) {
    return new Function<TypeResolver, AlternateTypeRule>() {

      @Override
      public AlternateTypeRule apply(TypeResolver typeResolver) {
        return AlternateTypeRules.newRule(typeResolver.resolve(clazz), typeResolver.resolve(with));
      }
    };
  }

  private Function<TypeResolver, AlternateTypeRule> newGenericSubstitutionFunction(final Class clz) {
    return new Function<TypeResolver, AlternateTypeRule>() {
      @Override
      public AlternateTypeRule apply(TypeResolver typeResolver) {
        return AlternateTypeRules.newRule(typeResolver.resolve(clz, WildcardType.class), typeResolver.resolve
                (WildcardType.class));
      }
    };
  }

  private void configureDefaults() {
    if (!hasText(this.groupName)) {
      this.groupName = DEFAULT_GROUP_NAME;
    }

    if (null == this.apiInfo) {
      this.apiInfo = ApiInfo.DEFAULT;
    }
  }

}
