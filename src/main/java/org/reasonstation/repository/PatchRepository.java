package org.reasonstation.repository;

import org.reasonstation.entity.Patch;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PatchRepository extends CrudRepository<Patch, Long> {
    List<Patch> findByUseridOrderByDateUploadedDesc(Long userid);

    List<Patch> findByArtistAliasOrderByDateUploadedDesc(String artistAlias);
}
