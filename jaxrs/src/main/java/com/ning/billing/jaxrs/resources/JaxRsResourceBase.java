/*
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.jaxrs.resources;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.ning.billing.jaxrs.json.CustomFieldJson;
import com.ning.billing.jaxrs.util.JaxrsUriBuilder;
import com.ning.billing.jaxrs.util.TagHelper;
import com.ning.billing.util.api.CustomFieldUserApi;
import com.ning.billing.util.api.TagDefinitionApiException;
import com.ning.billing.util.api.TagUserApi;
import com.ning.billing.util.callcontext.CallContext;
import com.ning.billing.util.customfield.CustomField;
import com.ning.billing.util.customfield.StringCustomField;
import com.ning.billing.util.dao.ObjectType;
import com.ning.billing.util.tag.Tag;
import com.ning.billing.util.tag.TagDefinition;

import javax.ws.rs.core.Response;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public abstract class JaxRsResourceBase implements JaxrsResource {
    private final JaxrsUriBuilder uriBuilder;
    private final TagUserApi tagUserApi;
    private final TagHelper tagHelper;
    private final CustomFieldUserApi customFieldUserApi;

    protected abstract ObjectType getObjectType();

    public JaxRsResourceBase(final JaxrsUriBuilder uriBuilder,
                             final TagUserApi tagUserApi,
                             final TagHelper tagHelper,
                             final CustomFieldUserApi customFieldUserApi) {
        this.uriBuilder = uriBuilder;
        this.tagUserApi = tagUserApi;
        this.tagHelper = tagHelper;
        this.customFieldUserApi = customFieldUserApi;
    }

    protected Response getTags(final UUID id) {
        Map<String, Tag> tags = tagUserApi.getTags(id, getObjectType());
        Collection<String> tagNameList = (tags.size() == 0) ?
                Collections.<String>emptyList() :
            Collections2.transform(tags.values(), new Function<Tag, String>() {
                @Override
                public String apply(Tag input) {
                    return input.getTagDefinitionName();
                }
            });
        return Response.status(Response.Status.OK).entity(tagNameList).build();
    }

    protected Response createTags(final UUID id,
                                  final String tagList,
                                  final CallContext context) {
        try {
            Preconditions.checkNotNull(tagList, "Query % list cannot be null", JaxrsResource.QUERY_TAGS);

            List<TagDefinition> input = tagHelper.getTagDefinitionFromTagList(tagList);
            tagUserApi.addTags(id, getObjectType(), input, context);

            return uriBuilder.buildResponse(this.getClass(), "createTags", id);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (NullPointerException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (TagDefinitionApiException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    protected Response deleteTags(final UUID id,
                                  final String tagList,
                                  final CallContext context) {

        try {
            List<TagDefinition> input = tagHelper.getTagDefinitionFromTagList(tagList);
            tagUserApi.removeTags(id, getObjectType(), input, context);

            return Response.status(Response.Status.OK).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (NullPointerException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (TagDefinitionApiException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    protected Response getCustomFields(final UUID id) {
        Map<String, CustomField> fields = customFieldUserApi.getCustomFields(id, getObjectType());

        List<CustomFieldJson> result = new LinkedList<CustomFieldJson>();
        for (CustomField cur : fields.values()) {
            result.add(new CustomFieldJson(cur));
        }
        return Response.status(Response.Status.OK).entity(result).build();
    }

    protected Response createCustomFields(final UUID id,
                                          final List<CustomFieldJson> customFields,
                                          final CallContext context) {
        try {
            LinkedList<CustomField> input = new LinkedList<CustomField>();
            for (CustomFieldJson cur : customFields) {
                input.add(new StringCustomField(cur.getName(), cur.getValue()));
            }

            customFieldUserApi.saveCustomFields(id, getObjectType(), input, context);
            return uriBuilder.buildResponse(this.getClass(), "createCustomFields", id);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (NullPointerException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    protected Response deleteCustomFields(final UUID id,
                                          final String customFieldList,
                                          final CallContext context) {
        try {
            // STEPH missing API to delete custom fields
            return Response.status(Response.Status.OK).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (NullPointerException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }
}
