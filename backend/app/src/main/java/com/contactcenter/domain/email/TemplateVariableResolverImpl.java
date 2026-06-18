package com.contactcenter.domain.email;

import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.customer.Customer;
import com.contactcenter.domain.user.UserService;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class TemplateVariableResolverImpl implements TemplateVariableResolver {

    private final ContactService contactService;
    private final CustomerService customerService;
    private final UserService userService;

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> resolveForContext(UUID contactId, UUID agentId) {
        return resolveForContext(contactId, null, agentId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> resolveForContext(UUID contactId, UUID customerId, UUID agentId) {
        UUID tenantId = TenantContext.getTenantId();
        Map<String, Object> vars = new HashMap<>();

        if (customerId != null) {
            resolveCustomerVarsByCustomerId(customerId, tenantId, vars);
        } else {
            resolveCustomerVars(contactId, tenantId, vars);
        }
        resolveAgentVars(agentId, tenantId, vars);

        log.debug("[TemplateVariableResolver] Resolved {} variables for contactId={}, customerId={}, agentId={}",
                vars.size(), contactId, customerId, agentId);
        return vars;
    }

    @Override
    public Map<String, Object> mergeWithCustom(Map<String, Object> predefined, Map<String, Object> custom) {
        Map<String, Object> merged = new HashMap<>(predefined);
        if (custom != null) {
            merged.putAll(custom);
        }
        return merged;
    }

    private void resolveCustomerVars(UUID contactId, UUID tenantId, Map<String, Object> vars) {
        if (contactId == null) {
            putEmptyCustomerVars(vars);
            return;
        }

        Optional<Contact> contactOpt = contactService.findContactEntity(contactId, tenantId);
        if (contactOpt.isEmpty() || contactOpt.get().getCustomerId() == null) {
            putEmptyCustomerVars(vars);
            return;
        }

        UUID customerId = contactOpt.get().getCustomerId();
        resolveCustomerVarsByCustomerId(customerId, tenantId, vars);
    }

    private void resolveCustomerVarsByCustomerId(UUID customerId, UUID tenantId, Map<String, Object> vars) {
        Optional<Customer> customerOpt = customerService.findById(customerId, tenantId);
        if (customerOpt.isEmpty()) {
            putEmptyCustomerVars(vars);
            return;
        }

        Customer customer = customerOpt.get();
        String firstName = nullToEmpty(customer.getFirstName());
        String lastName = nullToEmpty(customer.getLastName());

        vars.put(PredefinedTemplateVariable.CUSTOMER_FIRST_NAME.getKey(), firstName);
        vars.put(PredefinedTemplateVariable.CUSTOMER_LAST_NAME.getKey(), lastName);
        vars.put(PredefinedTemplateVariable.CUSTOMER_FULL_NAME.getKey(), buildFullName(firstName, lastName));
        vars.put(PredefinedTemplateVariable.CUSTOMER_EMAIL.getKey(), firstOrEmpty(customer.getEmail()));
        vars.put(PredefinedTemplateVariable.CUSTOMER_PHONE.getKey(), firstOrEmpty(customer.getPhone()));
    }

    private void resolveAgentVars(UUID agentId, UUID tenantId, Map<String, Object> vars) {
        if (agentId == null) {
            putEmptyAgentVars(vars);
            return;
        }

        Optional<AppUser> agentOpt = userService.findAgentByIdAndTenantId(agentId, tenantId);
        if (agentOpt.isEmpty()) {
            putEmptyAgentVars(vars);
            return;
        }

        AppUser agent = agentOpt.get();
        String firstName = nullToEmpty(agent.getFirstName());
        String lastName = nullToEmpty(agent.getLastName());

        vars.put(PredefinedTemplateVariable.AGENT_FIRST_NAME.getKey(), firstName);
        vars.put(PredefinedTemplateVariable.AGENT_LAST_NAME.getKey(), lastName);
        vars.put(PredefinedTemplateVariable.AGENT_FULL_NAME.getKey(), buildFullName(firstName, lastName));
        vars.put(PredefinedTemplateVariable.AGENT_EMAIL.getKey(), nullToEmpty(agent.getEmail()));
    }

    private void putEmptyCustomerVars(Map<String, Object> vars) {
        vars.put(PredefinedTemplateVariable.CUSTOMER_FIRST_NAME.getKey(), "");
        vars.put(PredefinedTemplateVariable.CUSTOMER_LAST_NAME.getKey(), "");
        vars.put(PredefinedTemplateVariable.CUSTOMER_FULL_NAME.getKey(), "");
        vars.put(PredefinedTemplateVariable.CUSTOMER_EMAIL.getKey(), "");
        vars.put(PredefinedTemplateVariable.CUSTOMER_PHONE.getKey(), "");
    }

    private void putEmptyAgentVars(Map<String, Object> vars) {
        vars.put(PredefinedTemplateVariable.AGENT_FIRST_NAME.getKey(), "");
        vars.put(PredefinedTemplateVariable.AGENT_LAST_NAME.getKey(), "");
        vars.put(PredefinedTemplateVariable.AGENT_FULL_NAME.getKey(), "");
        vars.put(PredefinedTemplateVariable.AGENT_EMAIL.getKey(), "");
    }

    private String firstOrEmpty(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        String value = list.get(0);
        return value != null ? value : "";
    }

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private String buildFullName(String firstName, String lastName) {
        String full = (firstName + " " + lastName).trim();
        return full;
    }
}
