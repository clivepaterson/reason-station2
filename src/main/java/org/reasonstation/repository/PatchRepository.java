package org.reasonstation.repository;

import org.reasonstation.entity.Patch;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PatchRepository extends CrudRepository<Patch, Long> {
}
