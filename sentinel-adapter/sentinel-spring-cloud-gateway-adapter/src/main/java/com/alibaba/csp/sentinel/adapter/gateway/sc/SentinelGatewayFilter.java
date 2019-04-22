/*
 * Copyright 1999-2019 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.adapter.gateway.sc;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.adapter.reactor.ContextConfig;
import com.alibaba.csp.sentinel.adapter.reactor.EntryConfig;
import com.alibaba.csp.sentinel.adapter.reactor.SentinelReactorTransformer;
import com.alibaba.csp.sentinel.adapter.gateway.sc.api.GatewayApiMatcherManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.api.matcher.WebExchangeApiMatcher;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayParamFlowItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.util.function.Predicate;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * @author Eric Zhao
 * @since 1.6.0
 */
public class SentinelGatewayFilter implements GatewayFilter, GlobalFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);

        Mono<Void> asyncResult = chain.filter(exchange);
        if (route != null) {
            String routeId = route.getId();
            Object[] params = parseParameterFor(routeId, exchange,
                r -> r.getResourceMode() == SentinelGatewayConstants.RESOURCE_MODE_ROUTE_ID);
            String origin = Optional.ofNullable(GatewayCallbackManager.getRequestOriginParser())
                .map(f -> f.apply(exchange))
                .orElse("");
            asyncResult = asyncResult.transform(
                new SentinelReactorTransformer<>(new EntryConfig(routeId, EntryType.IN,
                    1, params, new ContextConfig(contextName(routeId), origin)))
            );
        }

        Set<String> matchingApis = pickMatchingApiDefinitions(exchange);
        for (String apiName : matchingApis) {
            Object[] params = parseParameterFor(apiName, exchange,
                r -> r.getResourceMode() == SentinelGatewayConstants.RESOURCE_MODE_CUSTOM_API_NAME);
            asyncResult = asyncResult.transform(
                new SentinelReactorTransformer<>(new EntryConfig(apiName, EntryType.IN, 1, params))
            );
        }

        return asyncResult;
    }

    private String contextName(String route) {
        return SentinelGatewayConstants.GATEWAY_CONTEXT_ROUTE_PREFIX + route;
    }

    Set<String> pickMatchingApiDefinitions(ServerWebExchange exchange) {
        return GatewayApiMatcherManager.getApiMatcherMap().values()
            .stream()
            .filter(m -> m.test(exchange))
            .map(WebExchangeApiMatcher::getApiName)
            .collect(Collectors.toSet());
    }

    Object[] parseParameterFor(String resource, ServerWebExchange exchange,
                                       Predicate<GatewayFlowRule> rulePredicate) {
        Set<GatewayFlowRule> gatewayRules = GatewayRuleManager.getRulesForResource(resource)
            .stream()
            .filter(e -> e.getParamItem() != null)
            .collect(Collectors.toSet());
        if (gatewayRules.isEmpty()) {
            return new Object[0];
        }
        Set<Boolean> predSet = gatewayRules.stream()
            .map(rulePredicate::test)
            .collect(Collectors.toSet());
        if (predSet.size() != 1 || predSet.contains(false)) {
            return new Object[0];
        }
        Object[] arr = new Object[gatewayRules.size()];
        for (GatewayFlowRule rule : gatewayRules) {
            GatewayParamFlowItem paramItem = rule.getParamItem();
            int idx = paramItem.getIndex();
            String param = parseInternal(paramItem, exchange);
            arr[idx] = param;
        }
        return arr;
    }

    private String parseInternal(GatewayParamFlowItem item, ServerWebExchange exchange) {
        switch (item.getParseStrategy()) {
            case SentinelGatewayConstants.PARAM_PARSE_STRATEGY_CLIENT_IP:
                return parseClientIp(item, exchange);
            case SentinelGatewayConstants.PARAM_PARSE_STRATEGY_HOST:
                return parseHost(item, exchange);
            case SentinelGatewayConstants.PARAM_PARSE_STRATEGY_HEADER:
                return parseHeader(item, exchange);
            case SentinelGatewayConstants.PARAM_PARSE_STRATEGY_URL_PARAM:
                return parseUrlParameter(item, exchange);
            default:
                return null;
        }
    }

    private String parseClientIp(/*@Valid*/ GatewayParamFlowItem item, ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null) {
            return null;
        }
        String clientIp = remoteAddress.getAddress().getHostAddress();
        String pattern = item.getPattern();
        if (pattern == null) {
            return clientIp;
        }
        return parseWithMatchStrategyInternal(item.getMatchStrategy(), clientIp, pattern);
    }

    private String parseHeader(/*@Valid*/ GatewayParamFlowItem item, ServerWebExchange exchange) {
        String headerKey = item.getFieldName();
        String pattern = item.getPattern();
        // TODO: what if the header has multiple values?
        String headerValue = exchange.getRequest().getHeaders().getFirst(headerKey);
        if (pattern == null) {
            return headerValue;
        }
        // Match value according to regex pattern or exact mode.
        return parseWithMatchStrategyInternal(item.getMatchStrategy(), headerValue, pattern);
    }

    private String parseHost(/*@Valid*/ GatewayParamFlowItem item, ServerWebExchange exchange) {
        String pattern = item.getPattern();
        String host = exchange.getRequest().getHeaders().getFirst(HttpHeaders.HOST);
        if (pattern == null) {
            return host;
        }
        // Match value according to regex pattern or exact mode.
        return parseWithMatchStrategyInternal(item.getMatchStrategy(), host, pattern);
    }

    private String parseUrlParameter(/*@Valid*/ GatewayParamFlowItem item, ServerWebExchange exchange) {
        String paramName = item.getFieldName();
        String pattern = item.getPattern();
        String param = exchange.getRequest().getQueryParams().getFirst(paramName);
        if (pattern == null) {
            return param;
        }
        // Match value according to regex pattern or exact mode.
        return parseWithMatchStrategyInternal(item.getMatchStrategy(), param, pattern);
    }

    private String parseWithMatchStrategyInternal(int matchStrategy, String value, String pattern) {
        // TODO: implement here.
        if (value == null) {
            return null;
        }
        if (matchStrategy == SentinelGatewayConstants.PARAM_MATCH_STRATEGY_REGEX) {
            return value;
        }
        return value;
    }
}
