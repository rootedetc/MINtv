package local.mintv.search;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import local.mintv.net.BffJson;

/** Person result / cast member. */
public final class Person {

    public final String id;
    public final String name;
    public final String imageUrl;
    public final List<String> roles;
    public final String roleName;

    private Person(String id, String name, String imageUrl, List<String> roles,
                   String roleName) {
        this.id = id;
        this.name = name;
        this.imageUrl = imageUrl;
        this.roles = roles;
        this.roleName = roleName;
    }

    public static Person fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        String id = BffJson.str(json, "id", "person_id", "personId");
        String name = BffJson.str(json, "person_name", "personName", "name", "title");
        List<String> roles = new ArrayList<>();
        JSONArray array = json.optJSONArray("roles");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String role = BffJson.clean(array.optString(i, ""));
                if (!role.isEmpty()) {
                    roles.add(role);
                }
            }
        }
        String roleName = BffJson.str(json, "role_name", "roleName");
        if (roles.isEmpty() && !roleName.isEmpty()) {
            roles.add(roleName);
        }
        return new Person(id, name, BffJson.image(json),
                Collections.unmodifiableList(roles), roleName);
    }

    public String roleText() {
        if (!roles.isEmpty()) {
            return String.join(", ", roles);
        }
        return roleName;
    }
}
