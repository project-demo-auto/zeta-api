package xyz.erupt.zeta_api.service;

import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;
import org.dom4j.Attribute;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import xyz.erupt.zeta_api.config.ZetaApiProp;
import xyz.erupt.zeta_api.constant.TagConst;
import xyz.erupt.zeta_api.handler.ZetaCache;
import xyz.erupt.zeta_api.handler.ZetaApiHandler;
import xyz.erupt.zeta_api.impl.ZetaApi;
import xyz.erupt.zeta_api.tag.EleTag;
import xyz.erupt.zeta_api.tag.RootTag;
import xyz.erupt.zeta_api.util.IpUtil;
import xyz.erupt.zeta_api.util.NotFountException;
import xyz.erupt.zeta_api.util.ZetaApiSpringUtil;

import javax.annotation.Resource;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.MalformedURLException;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * @author liyuepeng
 * @date 2019-12-22
 */
@Service
public class ZetaApiService {

    @Resource
    private HttpServletRequest request;

    @Resource
    private HttpServletResponse response;

    @Resource
    private ZetaApiProp zetaApiProp;

    private final ScriptEngineManager scriptEngineManager = new ScriptEngineManager();

    public static final String REQUEST_BODY_KEY = "$requestBody";

    private static final String QUERY_FEATURES = "select";

    private final Map<String, Document> xmlDocuments = new ConcurrentHashMap<>();

    public Object action(String fileName, String elementName, ZetaApi zetaApi, Map<String, Object> params) {
        return xmlToQuery(fileName, elementName, (element, expression) -> {
            if (expression.startsWith(QUERY_FEATURES) || expression.startsWith(QUERY_FEATURES.toUpperCase())) {
                Attribute cacheAttr = element.attribute(EleTag.CACHE);
                if (null != cacheAttr && zetaApiProp.isEnableCache()) {
                    {
                        String cacheKey = fileName + "_" + elementName;
                        ZetaCache cacheHandler;
                        try {
                            cacheHandler = ZetaApiSpringUtil.getBeanByPath(zetaApiProp.getCacheHandlerPath(), ZetaCache.class);
                        } catch (ClassNotFoundException e) {
                            throw new RuntimeException(e);
                        }
                        Object result = cacheHandler.get(cacheKey, params);
                        if (null == result) {
                            result = zetaApi.query(element, expression, params);
                            cacheHandler.put(cacheKey, params, result, Long.valueOf(cacheAttr.getValue()));
                        }
                        return result;
                    }
                } else {
                    return zetaApi.query(element, expression, params);
                }
            } else {
                return zetaApi.modify(element, expression, params);
            }
        });
    }

    // 校验ip白名单
    public boolean validateIpWhite() {
        List<String> ipWhite = zetaApiProp.getIpWhite();
        if (null != ipWhite && ipWhite.size() > 0) {
            String reqIp = IpUtil.getIpAddr(request);
            for (String ip : ipWhite) {
                if (ip.equals(reqIp)) {
                    return true;
                }
            }
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return false;
        } else {
            return true;
        }
    }

    public Document getXmlDocument(String fileName) {
        try {
            if (zetaApiProp.isHotReadXml()) {
                return new SAXReader().read(this.getClass().getResourceAsStream(
                        zetaApiProp.getXmlbasePath() + "/" + fileName + ".xml"));
            } else {
                if (xmlDocuments.containsKey(fileName)) {
                    return xmlDocuments.get(fileName);
                } else {
                    Document document = new SAXReader().read(this.getClass().getResourceAsStream(
                            zetaApiProp.getXmlbasePath() + "/" + fileName + ".xml"));
                    xmlDocuments.put(fileName, document);
                    return document;
                }
            }
        } catch (DocumentException e) {
            if (e.getCause() instanceof MalformedURLException) {
                throw new NotFountException("not found " + fileName + " document");
            } else {
                throw new RuntimeException(e.getMessage());
            }
        }
    }

    private Object xmlToQuery(String fileName, String elementName, BiFunction<Element, String, Object> function) {
        Element rootElement = getXmlDocument(fileName).getRootElement();
        Element element = rootElement.element(elementName);
        if (null == element) {
            throw new NotFountException("not found " + elementName + " element");
        }
        String expression = parseElement(element);
        ZetaApiHandler zetaApiHandler = getRootHandler(rootElement);
        if (null != zetaApiHandler) {
            expression = zetaApiHandler.handler(element, expression);
        }
        Object result = function.apply(element, expression);
        if (null != zetaApiHandler) {
            return zetaApiHandler.handlerResult(element, result);
        }
        return result;
    }

    @SneakyThrows
    private String parseElement(Element element) {
        String content = element.getTextTrim();
        List<Element> list = element.elements();
        if (list.size() > 0) {
            StringBuilder sb = new StringBuilder(content);
            ScriptEngine js = scriptEngineManager.getEngineByName("JavaScript");
            Object paramObj = request.getAttribute(ZetaApiService.REQUEST_BODY_KEY);
            if (paramObj != null) {
                Map<String, Object> param = (Map<String, Object>) paramObj;
                for (Map.Entry<String, Object> entry : param.entrySet()) {
                    js.put(entry.getKey(), entry.getValue());
                }
            }
            for (Element ele : list) {
                if (TagConst.IfTag.NAME.equals(ele.getName())) {
                    Attribute testAttr = ele.attribute(TagConst.IfTag.ATTR_TEST);
                    if (null != testAttr) {
                        if ((Boolean) js.eval(String.format("!!(%s)", testAttr.getValue()))) {
                            sb.append(" ").append(ele.getTextTrim());
                        }
                    } else {
                        sb.append(" ").append(ele.getTextTrim());
                    }
                }
            }
            return sb.toString();
        } else {
            return content;
        }
    }


    private ZetaApiHandler getRootHandler(Element element) {
        Attribute handlerAttr = element.attribute(RootTag.HANDLER);
        if (null != handlerAttr) {
            try {
                return (ZetaApiHandler) ZetaApiSpringUtil.getBean(Class.forName(handlerAttr.getValue()));
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public Map<String, String> getReqMap() {
        Enumeration<String> parameterNames = request.getParameterNames();
        Map<String, String> map = new TreeMap<>();
        while (parameterNames.hasMoreElements()) {
            String parameterName = parameterNames.nextElement();
            String val = request.getParameter(parameterName);
            if (StringUtils.isBlank(val)) {
                val = "";
            }
            map.put(parameterName, val);
        }
        return map;
    }
}
