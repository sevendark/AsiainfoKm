package com.asiainfo.km.service.impl;

import com.asiainfo.km.domain.BugInfo;
import com.asiainfo.km.domain.DocInfo;
import com.asiainfo.km.domain.LogInfo;
import com.asiainfo.km.domain.solr.DocSolrInfo;
import com.asiainfo.km.pojo.*;
import com.asiainfo.km.repository.DocRepo;
import com.asiainfo.km.repository.LogRepo;
import com.asiainfo.km.service.BugService;
import com.asiainfo.km.service.DocRepoService;
import com.asiainfo.km.service.DocService;
import com.asiainfo.km.settings.PathSettings;
import com.asiainfo.km.util.KmResultCreater;
import com.asiainfo.km.util.OsFileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.activation.MimetypesFileTypeMap;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static com.asiainfo.km.util.OsFileUtil.getMd5;

/**
 * Created by jiyuze on 2017/8/3.
 */
@Service
@Transactional
public class DocSvcRepoImp implements DocRepoService {
    private static final Logger logger = LoggerFactory.getLogger(DocSvcRepoImp.class);
    private final DocRepo docRepo;
    private final PathSettings pathSettings;
    private final LogRepo logRepo;
    private final BugService bugService;
    private final DocService<DocSolrInfo> docSolrService;

    @Autowired
    public DocSvcRepoImp(DocRepo docRepo, PathSettings pathSettings, LogRepo logRepo, BugService bugService, DocService<DocSolrInfo> docSolrService) {
        this.docRepo = docRepo;
        this.pathSettings = pathSettings;
        this.logRepo = logRepo;
        this.bugService = bugService;
        this.docSolrService = docSolrService;
    }

    @Deprecated
    public TableResult<DocInfo> getDocList4Table(TableParam param) {
        throw new UnsupportedOperationException();
    }

    public DocInfo saveDoc(DocInfo docInfo) throws KmException {
        docInfo.setCreateTime(new Timestamp(System.currentTimeMillis()));
        docInfo.setSearchTimes(0);
        docInfo.setDocTimes(0);
        docInfo = docRepo.save(docInfo);
        File file = new File(docInfo.getPath());
        docInfo.setMsgDigest(getMd5(file));

        DocSolrInfo solrInfo = new DocSolrInfo();
        solrInfo.setDocId(docInfo.getDocId());
        solrInfo.setDocMime(docInfo.getDocMime());
        solrInfo.setDocName(docInfo.getDocName());
        solrInfo.setCreateUser(docInfo.getCreateUser());
        solrInfo.setCreateTime(docInfo.getCreateTime().getTime());
        docSolrService.saveDoc(solrInfo);

        KmResult<DocSolrInfo> result = docSolrService.getOneDoc(docInfo.getDocId());
        DocSolrInfo solrDoc = result.getData();
        docInfo.setDocSize(solrDoc.getDocSize());
        docInfo.setDocIntro(solrDoc.getDocIntro());
        docInfo.setDocMime(solrDoc.getDocMime());
        docInfo = docRepo.saveAndFlush(docInfo);

        return docInfo;
    }

    public KmResult<DocInfo> getOneDoc(Long docId) {
        addSearchTimes(docId);
        DocInfo info = docRepo.findOne(docId);
        if (info == null) {
            return KmResultCreater.createError();
        }
        return KmResultCreater.createSuccess(info);
    }

    public DocInfo getDocByMd5(String md5) {
        List<DocInfo> list = docRepo.findByMsgDigest(md5);
        if (list.size() == 0) {
            return null;
        }
        return list.get(0);
    }

    public void addSearchTimes(Long docId) {
        DocInfo info = docRepo.findOne(docId);
        if (info != null) {
            info.setSearchTimes(info.getSearchTimes() + 1);
            docRepo.save(info);
        }

    }

    @Override
    public void deleteDoc(Long docId) {
        DocInfo info = docRepo.findOne(docId);
        if (info != null) {
            docRepo.delete(info.getDocId());
            docSolrService.deleteDoc(docId);
        }
    }

    public void removeDocBug(Long docId, Long bugId) {
        DocInfo info = docRepo.findOne(docId);
        List<BugInfo> list = info.getBugList();
        for (BugInfo e : list) {
            if (e.getBugId().equals(bugId)) {
                list.remove(e);
                docRepo.save(info);
                return;
            }
        }
    }

    public void addDocBug(Long docId, Long bugId) {
        DocInfo info = docRepo.findOne(docId);
        BugInfo bugInfo = bugService.getBugInfo(bugId);
        info.getBugList().add(bugInfo);

    }

    public Integer upTimes(Long docId, String user, String context) {
        LogInfo log = new LogInfo();
        log.setCreateTime(new Timestamp(System.currentTimeMillis()));
        log.setCreateUser(user);
        log.setLogContext(context);
        log.setValue(1);

        DocInfo doc = docRepo.findOne(docId);
        doc.setDocTimes(doc.getDocTimes() + 1);
        doc.getLogList().add(logRepo.save(log));
        docRepo.save(doc);
        return doc.getDocTimes();
    }

    public Integer subTimes(Long docId, String user, String context) {
        LogInfo log = new LogInfo();
        log.setCreateTime(new Timestamp(System.currentTimeMillis()));
        log.setCreateUser(user);
        log.setLogContext(context);
        log.setValue(-1);

        DocInfo doc = docRepo.findOne(docId);
        if ((doc.getDocTimes() - 1) >= 0) {
            doc.setDocTimes(doc.getDocTimes() - 1);
            doc.getLogList().add(logRepo.save(log));
            docRepo.save(doc);
        }
        return doc.getDocTimes();
    }

    public List<String> readPath() throws KmException {
        List<String> files = new ArrayList<>();
        readPath(OsFileUtil.newFileByOs(pathSettings.getLocalRoot(), ""), files);
        return files;
    }

    public void readPath(File path, List<String> files) throws KmException {
        File[] list = path.listFiles();
        if (list != null) {
            for (File file$folder : list) {
                if (file$folder.isDirectory()) {
                    if (!file$folder.getPath().contains(".svn")) {
                        readPath(file$folder, files);
                    }
                } else if (file$folder.isFile()) {
                    String path$name = file$folder.getPath();
                    String type = new MimetypesFileTypeMap().getContentType(file$folder.getName());
                    DocInfo docInfo = docRepo.findByPath(path$name);
                    if (docInfo == null) {
                        files.add(file$folder.getName());
                        DocInfo info = new DocInfo();
                        info.setDocMime(type);
                        info.setPath(path$name);
                        info.setDocName(file$folder.getName());
                        info.setCreateUser("系统导入");
                        saveDoc(info);
                    }
                }
            }
        }
    }

    public Folder getFolderList() {
        Folder root = new Folder();
        getFolderList(Paths.get(pathSettings.getLocalRoot()), root);
        return root;
    }

    private void getFolderList(Path path, Folder me) {
        me.setPath(path.toString());
        me.setName(path.getFileName().toString());
        try {
            Files.walk(path, 1)
                    .filter(e -> Files.isDirectory(e))
                    .filter(e -> !path.equals(e))
                    .filter(e -> !e.getFileName().toString().startsWith("."))
                    .forEach(e -> {
                        Folder child = new Folder();
                        me.getChildren().add(child);
                        getFolderList(e, child);
                    });
        } catch (IOException e) {
            logger.error("can't read folder list", e);
        }
    }

}
