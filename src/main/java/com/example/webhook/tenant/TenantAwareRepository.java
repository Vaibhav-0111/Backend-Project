package com.example.webhook.tenant;

import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

@NoRepositoryBean
public interface TenantAwareRepository<T, ID> extends Repository<T, ID> {
    
    Optional<T> findByIdAndTenantId(ID id, String tenantId);
    
    List<T> findAllByTenantId(String tenantId);
    
    T save(T entity);
    
    void deleteByIdAndTenantId(ID id, String tenantId);
    
    long countByTenantId(String tenantId);
}
