package com.hack.segmentrec.service.query;

import java.util.Set;

public interface QueryUnderstandingProvider {

    QueryParseResult parse(String query, String explicitIndustry, Set<String> knownIndustries);
}
