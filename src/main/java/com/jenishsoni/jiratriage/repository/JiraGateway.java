package com.jenishsoni.jiratriage.repository;

import com.jenishsoni.jiratriage.domain.Issue;
import com.jenishsoni.jiratriage.domain.Change;
import com.jenishsoni.jiratriage.domain.DuplicateLink;
import com.jenishsoni.jiratriage.domain.IssuePatch;
import com.jenishsoni.jiratriage.duplicate.DuplicateSearchResult;
import java.util.List;
import java.util.Optional;

public interface JiraGateway {

    List<Issue> findAll();

    Optional<Issue> findByKey(String key);

    DuplicateSearchResult findDuplicates(String key);

    Change<Issue> update(String key, IssuePatch patch, boolean dryRun);

    Change<DuplicateLink> linkDuplicate(String issueKey, String duplicateOfKey, boolean dryRun);

    List<DuplicateLink> findDuplicateLinks();
}
