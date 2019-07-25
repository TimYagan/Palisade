/*
 * Copyright 2018 Crown Copyright
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

package uk.gov.gchq.palisade.policy.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import uk.gov.gchq.palisade.Context;
import uk.gov.gchq.palisade.User;
import uk.gov.gchq.palisade.Util;
import uk.gov.gchq.palisade.cache.service.CacheService;
import uk.gov.gchq.palisade.cache.service.request.AddCacheRequest;
import uk.gov.gchq.palisade.cache.service.request.GetCacheRequest;
import uk.gov.gchq.palisade.exception.NoConfigException;
import uk.gov.gchq.palisade.jsonserialisation.JSONSerialiser;
import uk.gov.gchq.palisade.policy.service.MultiPolicy;
import uk.gov.gchq.palisade.policy.service.Policy;
import uk.gov.gchq.palisade.policy.service.PolicyService;
import uk.gov.gchq.palisade.policy.service.request.CanAccessRequest;
import uk.gov.gchq.palisade.policy.service.request.GetPolicyRequest;
import uk.gov.gchq.palisade.policy.service.request.SetResourcePolicyRequest;
import uk.gov.gchq.palisade.policy.service.request.SetTypePolicyRequest;
import uk.gov.gchq.palisade.policy.service.response.CanAccessResponse;
import uk.gov.gchq.palisade.resource.ChildResource;
import uk.gov.gchq.palisade.resource.LeafResource;
import uk.gov.gchq.palisade.resource.Resource;
import uk.gov.gchq.palisade.rule.Rules;
import uk.gov.gchq.palisade.service.ServiceState;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;

/**
 * By having the policies stored in several key value stores we can attach policies
 * at either the resource or data type level.
 * Each rule needs to be flagged as a resource level filter, or a record level filter/transform.
 * To get the rules for a file/stream resource, you need to get the rules for the given resource
 * followed by the rules of all its parents. Then you get the rules of the given resources data type.
 * If there are any negation rules then all rules inherited from up the
 * chain should be checked to see if any rules need removing due to the negation rule.
 */
public class HierarchicalPolicyService implements PolicyService {
    private static final Logger LOGGER = LoggerFactory.getLogger(HierarchicalPolicyService.class);

    private static final String DATA_TYPE_POLICIES_PREFIX = "dataTypePolicy.";
    private static final String RESOURCE_POLICIES_PREFIX = "resourcePolicy.";

    public static final String CACHE_IMPL_KEY = "policy.svc.cache.svc";

    private CacheService cacheService;

    public HierarchicalPolicyService() {
    }

    public HierarchicalPolicyService cacheService(final CacheService cacheService) {
        requireNonNull(cacheService, "Cache service cannot be set to null.");
        this.cacheService = cacheService;
        return this;
    }

    public void setCacheService(final CacheService cacheService) {
        cacheService(cacheService);
    }

    public CacheService getCacheService() {
        requireNonNull(cacheService, "The cache service has not been set.");
        return cacheService;
    }

    @Override
    public void applyConfigFrom(final ServiceState config) throws NoConfigException {
        requireNonNull(config, "config");
        //extract cache
        String serialisedCache = config.getOrDefault(CACHE_IMPL_KEY, null);
        if (nonNull(serialisedCache)) {
            setCacheService(JSONSerialiser.deserialise(serialisedCache.getBytes(StandardCharsets.UTF_8), CacheService.class));
        } else {
            throw new NoConfigException("no cache service specified in configuration");
        }
    }

    @Override
    public void recordCurrentConfigTo(final ServiceState config) {
        requireNonNull(config, "config");
        config.put(PolicyService.class.getTypeName(), getClass().getTypeName());
        String serialisedCache = new String(JSONSerialiser.serialise(cacheService), StandardCharsets.UTF_8);
        config.put(CACHE_IMPL_KEY, serialisedCache);
    }

    @Override
    public CompletableFuture<CanAccessResponse> canAccess(final CanAccessRequest request) {
        Context context = request.getContext();
        User user = request.getUser();
        Collection<LeafResource> resources = request.getResources();
        CanAccessResponse response = new CanAccessResponse().canAccessResources(canAccess(context, user, resources));
        return CompletableFuture.completedFuture(response);
    }

    private Collection<LeafResource> canAccess(final Context context, final User user, final Collection<LeafResource> resources) {
        return resources.stream()
                .map(resource -> {
                    CompletableFuture<Optional<Rules<LeafResource>>> futureRules = getApplicableRules(resource, true, resource.getType());
                    Optional<Rules<LeafResource>> rules = futureRules.join();
                    if (rules.isPresent()) {
                        return Util.applyRulesToItem(resource, user, context, rules.get());
                    } else {
                        LOGGER.debug("No policy for {}, removing resource from list...", resource);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * This method is used to recursively go up the resource hierarchy ending with the original
     * data type to extract and merge the policies at each stage of the hierarchy.
     *
     * @param resource         A {@link Resource} to get the applicable rules for.
     * @param canAccessRequest A boolean that is true if you want the resource level.
     *                         rules and therefore this is called from the canAccess method
     * @param originalDataType This is the data type that you want to be at the top of the
     *                         Resource hierarchy tree, which will be the data type of the
     *                         first resource in the recursive calls to this method.
     * @param <T>              The type of the returned {@link Rules}.
     * @return A completable future of {@link Rules} object of type T, which contains the list of rules
     * that need to be applied to the resource.
     */
    protected <T> CompletableFuture<Optional<Rules<T>>> getApplicableRules(final Resource resource, final boolean canAccessRequest, final String originalDataType) {
        CompletableFuture<Optional<Rules<T>>> inheritedRules;
        if (resource instanceof ChildResource) {
            inheritedRules = getApplicableRules(((ChildResource) resource).getParent(), canAccessRequest, originalDataType);
        } else {
            //we are at top of hierarchy
            CompletableFuture<Optional<Policy>> inheritedPolicy = (CompletableFuture<Optional<Policy>>) getCacheService().get(
                    new GetCacheRequest<Policy>()
                            .service(this.getClass())
                            .key(DATA_TYPE_POLICIES_PREFIX + originalDataType));

            inheritedRules = inheritedPolicy.thenApply(policy -> extractRules(canAccessRequest, policy));
        }

        CompletableFuture<Optional<Policy>> newPolicy = (CompletableFuture<Optional<Policy>>) getCacheService().get(
                new GetCacheRequest<Policy>()
                        .service(this.getClass())
                        .key(RESOURCE_POLICIES_PREFIX + resource.getId()));

        return inheritedRules.thenCombine(newPolicy, (oldRules, policy) -> {
            Optional<Rules<T>> newRules = extractRules(canAccessRequest, policy);
            return mergeRules(oldRules, newRules);
        });
    }

    private <T> Optional<Rules<T>> extractRules(final boolean canAccessRequest, final Optional<Policy> policy) {
        if (canAccessRequest) {
            return policy.map(p -> p.getResourceRules());
        } else {
            return policy.map(p -> p.getRecordRules());
        }
    }

    private <T> Optional<Rules<T>> mergeRules(final Optional<Rules<T>> inheritedRules, final Optional<Rules<T>> newRules) {
        if (inheritedRules.isPresent()) {
            if (newRules.isPresent()) {
                //both present --> merge
                String inheritedMessage = inheritedRules.get().getMessage();
                String newMessage = newRules.get().getMessage();
                if (!inheritedMessage.equals(Rules.NO_RULES_SET) && !newMessage.equals(Rules.NO_RULES_SET)) {
                    inheritedRules.get().message(inheritedMessage + ", " + newMessage);
                } else if (!newMessage.equals(Rules.NO_RULES_SET)) {
                    inheritedRules.get().message(newMessage);
                }
                //don't test for inheritedRules != Rules.NO_RULES_SET as that is the default case, there is nothing to do
                inheritedRules.get().addRules(newRules.get().getRules());
                return inheritedRules;
            } else {
                //only inherited present
                return inheritedRules;
            }
        } else {
            if (newRules.isPresent()) {
                //new rules but no inherited ones
                return newRules;
            } else {
                //neither
                return Optional.empty();
            }
        }

    }

    @Override
    public CompletableFuture<MultiPolicy> getPolicy(final GetPolicyRequest request) {
        Context context = request.getContext();
        User user = request.getUser();
        Collection<LeafResource> resources = request.getResources();
        Collection<LeafResource> canAccessResources = canAccess(context, user, resources);
        /* Having filtered out any resources the user doesn't have access to in the line above, we now build the map
         * of resource to record level rule policies. If there are resource level rules for a record then there SHOULD
         * be record level rules. Either list may be empty, but they should at least be present!
         */
        HashMap<LeafResource, Policy> map = new HashMap<>();
        canAccessResources.forEach(resource -> {
            CompletableFuture<Optional<Rules<Object>>> rules = getApplicableRules(resource, false, resource.getType());
            Optional<Rules<Object>> optionalRecordRules = rules.join();
            if (optionalRecordRules.isPresent()) {
                map.put(resource, new Policy<>().recordRules(optionalRecordRules.get()));
            } else {
                LOGGER.warn("Couldn't find any record level rules for {}. This shouldn't be the case, since we found resource level rules for it!");
            }
        });
        return CompletableFuture.completedFuture(new MultiPolicy().policies(map));
    }

    @Override
    public CompletableFuture<Boolean> setResourcePolicy(final SetResourcePolicyRequest request) {
        requireNonNull(request);
        Resource resource = request.getResource();
        Policy policy = request.getPolicy();
        LOGGER.debug("Set {} to resource {}", policy, resource);
        return getCacheService().add(
                new AddCacheRequest<Policy>()
                        .service(this.getClass())
                        .key(RESOURCE_POLICIES_PREFIX + resource.getId())
                        .value(policy));
    }

    @Override
    public CompletableFuture<Boolean> setTypePolicy(final SetTypePolicyRequest request) {
        requireNonNull(request);
        final String type = request.getType();
        final Policy policy = request.getPolicy();
        LOGGER.debug("Set {} to data type {}", policy, type);
        return getCacheService().add(
                new AddCacheRequest<Policy>()
                        .service(this.getClass())
                        .key(DATA_TYPE_POLICIES_PREFIX + type)
                        .value(policy));
    }
}
