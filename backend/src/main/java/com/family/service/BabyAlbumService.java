package com.family.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.family.common.context.UserContext;
import com.family.entity.BabyAlbum;
import com.family.entity.BabyPhoto;
import com.family.mapper.BabyAlbumMapper;
import com.family.mapper.BabyPhotoMapper;
import com.family.vo.BabyAlbumVO;
import com.family.vo.BabyPhotoVO;
import com.family.vo.PageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 宝宝相册服务
 */
@Service
@RequiredArgsConstructor
public class BabyAlbumService {

    private final BabyAlbumMapper babyAlbumMapper;
    private final BabyPhotoMapper babyPhotoMapper;

    /**
     * 获取相册列表
     *
     * @param babyId 宝宝ID
     * @return 相册列表
     */
    public List<BabyAlbumVO> getAlbumList(Long babyId) {
        Long familyId = UserContext.getFamilyId();

        List<BabyAlbum> albums = babyAlbumMapper.selectList(
                new LambdaQueryWrapper<BabyAlbum>()
                        .eq(BabyAlbum::getBabyId, babyId)
                        .eq(BabyAlbum::getFamilyId, familyId)
                        .orderByDesc(BabyAlbum::getCreateTime)
                        .orderByDesc(BabyAlbum::getId)
        );

        return albums.stream()
                .map(this::convertAlbumToVO)
                .collect(Collectors.toList());
    }

    /**
     * 相册详情
     *
     * @param babyId  宝宝ID
     * @param albumId 相册ID
     * @return 相册详情
     */
    public BabyAlbumVO getAlbumDetail(Long babyId, Long albumId) {
        Long familyId = UserContext.getFamilyId();

        BabyAlbum album = babyAlbumMapper.selectOne(
                new LambdaQueryWrapper<BabyAlbum>()
                        .eq(BabyAlbum::getId, albumId)
                        .eq(BabyAlbum::getBabyId, babyId)
                        .eq(BabyAlbum::getFamilyId, familyId)
        );

        if (album == null) {
            return null;
        }

        return convertAlbumToVO(album);
    }

    /**
     * 创建相册
     *
     * @param babyId      宝宝ID
     * @param title       标题
     * @param description 描述
     * @return 新建的相册
     */
    @Transactional(rollbackFor = Exception.class)
    public BabyAlbumVO createAlbum(Long babyId, String title, String description) {
        Long familyId = UserContext.getFamilyId();
        LocalDateTime now = LocalDateTime.now();

        BabyAlbum album = new BabyAlbum();
        album.setBabyId(babyId);
        album.setFamilyId(familyId);
        album.setTitle(title);
        album.setDescription(description);
        album.setPhotoCount(0);
        album.setCreateTime(now);
        album.setUpdateTime(now);

        babyAlbumMapper.insert(album);
        return convertAlbumToVO(album);
    }

    /**
     * 修改相册
     *
     * @param babyId      宝宝ID
     * @param albumId     相册ID
     * @param title       标题
     * @param description 描述
     * @return 修改后的相册
     */
    @Transactional(rollbackFor = Exception.class)
    public BabyAlbumVO updateAlbum(Long babyId, Long albumId, String title, String description) {
        Long familyId = UserContext.getFamilyId();

        BabyAlbum album = babyAlbumMapper.selectOne(
                new LambdaQueryWrapper<BabyAlbum>()
                        .eq(BabyAlbum::getId, albumId)
                        .eq(BabyAlbum::getBabyId, babyId)
                        .eq(BabyAlbum::getFamilyId, familyId)
        );

        if (album == null) {
            return null;
        }

        if (title != null) {
            album.setTitle(title);
        }
        if (description != null) {
            album.setDescription(description);
        }
        album.setUpdateTime(LocalDateTime.now());

        babyAlbumMapper.updateById(album);
        return convertAlbumToVO(album);
    }

    /**
     * 删除相册（同时删除照片）
     *
     * @param babyId  宝宝ID
     * @param albumId 相册ID
     * @return 是否成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteAlbum(Long babyId, Long albumId) {
        Long familyId = UserContext.getFamilyId();

        BabyAlbum album = babyAlbumMapper.selectOne(
                new LambdaQueryWrapper<BabyAlbum>()
                        .eq(BabyAlbum::getId, albumId)
                        .eq(BabyAlbum::getBabyId, babyId)
                        .eq(BabyAlbum::getFamilyId, familyId)
        );

        if (album == null) {
            return false;
        }

        babyPhotoMapper.delete(
                new LambdaQueryWrapper<BabyPhoto>()
                        .eq(BabyPhoto::getAlbumId, albumId)
                        .eq(BabyPhoto::getBabyId, babyId)
                        .eq(BabyPhoto::getFamilyId, familyId)
        );

        return babyAlbumMapper.deleteById(albumId) > 0;
    }

    /**
     * 获取照片列表（分页）
     *
     * @param babyId  宝宝ID
     * @param albumId 相册ID
     * @param page    页码
     * @param size    每页大小
     * @return 分页照片列表
     */
    public PageVO<BabyPhotoVO> getPhotoList(Long babyId, Long albumId, int page, int size) {
        Long familyId = UserContext.getFamilyId();

        Page<BabyPhoto> pageParam = new Page<>(page, size);
        Page<BabyPhoto> photoPage = babyPhotoMapper.selectPage(pageParam,
                new LambdaQueryWrapper<BabyPhoto>()
                        .eq(BabyPhoto::getAlbumId, albumId)
                        .eq(BabyPhoto::getBabyId, babyId)
                        .eq(BabyPhoto::getFamilyId, familyId)
                        .orderByDesc(BabyPhoto::getPhotoDate)
                        .orderByDesc(BabyPhoto::getCreateTime)
                        .orderByDesc(BabyPhoto::getId)
        );

        List<BabyPhotoVO> records = photoPage.getRecords().stream()
                .map(this::convertPhotoToVO)
                .collect(Collectors.toList());

        return new PageVO<>(
                records,
                photoPage.getTotal(),
                photoPage.getCurrent(),
                photoPage.getSize(),
                photoPage.getPages()
        );
    }

    /**
     * 按日期获取照片
     *
     * @param babyId 宝宝ID
     * @param date   日期
     * @return 照片列表
     */
    public List<BabyPhotoVO> getPhotosByDate(Long babyId, LocalDate date) {
        Long familyId = UserContext.getFamilyId();

        List<BabyPhoto> photos = babyPhotoMapper.selectList(
                new LambdaQueryWrapper<BabyPhoto>()
                        .eq(BabyPhoto::getBabyId, babyId)
                        .eq(BabyPhoto::getFamilyId, familyId)
                        .eq(BabyPhoto::getPhotoDate, date)
                        .orderByAsc(BabyPhoto::getSortOrder)
                        .orderByDesc(BabyPhoto::getCreateTime)
        );

        return photos.stream()
                .map(this::convertPhotoToVO)
                .collect(Collectors.toList());
    }

    /**
     * 批量上传照片
     *
     * @param babyId    宝宝ID
     * @param albumId   相册ID
     * @param photoUrls 照片URL列表
     * @param title     标题（可选）
     * @param photoDate 拍摄日期
     * @return 上传的照片列表
     */
    @Transactional(rollbackFor = Exception.class)
    public List<BabyPhotoVO> uploadPhotos(Long babyId, Long albumId, List<String> photoUrls, String title, LocalDate photoDate) {
        Long familyId = UserContext.getFamilyId();
        Long userId = UserContext.getUserId();
        LocalDateTime now = LocalDateTime.now();

        BabyAlbum album = babyAlbumMapper.selectOne(
                new LambdaQueryWrapper<BabyAlbum>()
                        .eq(BabyAlbum::getId, albumId)
                        .eq(BabyAlbum::getBabyId, babyId)
                        .eq(BabyAlbum::getFamilyId, familyId)
        );

        if (album == null) {
            return List.of();
        }

        List<BabyPhoto> photos = photoUrls.stream().map(url -> {
            BabyPhoto photo = new BabyPhoto();
            photo.setBabyId(babyId);
            photo.setAlbumId(albumId);
            photo.setFamilyId(familyId);
            photo.setUrl(url);
            photo.setTitle(title);
            photo.setPhotoDate(photoDate);
            photo.setUploaderId(userId);
            photo.setSortOrder(0);
            photo.setCreateTime(now);
            photo.setUpdateTime(now);
            return photo;
        }).collect(Collectors.toList());

        for (BabyPhoto photo : photos) {
            babyPhotoMapper.insert(photo);
        }

        album.setPhotoCount(album.getPhotoCount() + photos.size());
        album.setUpdateTime(now);
        babyAlbumMapper.updateById(album);

        return photos.stream()
                .map(this::convertPhotoToVO)
                .collect(Collectors.toList());
    }

    /**
     * 修改照片信息
     *
     * @param babyId      宝宝ID
     * @param photoId     照片ID
     * @param title       标题
     * @param description 描述
     * @param photoDate   拍摄日期
     * @return 修改后的照片
     */
    @Transactional(rollbackFor = Exception.class)
    public BabyPhotoVO updatePhoto(Long babyId, Long photoId, String title, String description, LocalDate photoDate) {
        Long familyId = UserContext.getFamilyId();

        BabyPhoto photo = babyPhotoMapper.selectOne(
                new LambdaQueryWrapper<BabyPhoto>()
                        .eq(BabyPhoto::getId, photoId)
                        .eq(BabyPhoto::getBabyId, babyId)
                        .eq(BabyPhoto::getFamilyId, familyId)
        );

        if (photo == null) {
            return null;
        }

        if (title != null) {
            photo.setTitle(title);
        }
        if (description != null) {
            photo.setDescription(description);
        }
        if (photoDate != null) {
            photo.setPhotoDate(photoDate);
        }
        photo.setUpdateTime(LocalDateTime.now());

        babyPhotoMapper.updateById(photo);
        return convertPhotoToVO(photo);
    }

    /**
     * 删除照片
     *
     * @param babyId  宝宝ID
     * @param photoId 照片ID
     * @return 是否成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean deletePhoto(Long babyId, Long photoId) {
        Long familyId = UserContext.getFamilyId();

        BabyPhoto photo = babyPhotoMapper.selectOne(
                new LambdaQueryWrapper<BabyPhoto>()
                        .eq(BabyPhoto::getId, photoId)
                        .eq(BabyPhoto::getBabyId, babyId)
                        .eq(BabyPhoto::getFamilyId, familyId)
        );

        if (photo == null) {
            return false;
        }

        Long albumId = photo.getAlbumId();
        boolean deleted = babyPhotoMapper.deleteById(photoId) > 0;

        if (deleted && albumId != null) {
            BabyAlbum album = babyAlbumMapper.selectById(albumId);
            if (album != null && album.getPhotoCount() > 0) {
                album.setPhotoCount(album.getPhotoCount() - 1);
                album.setUpdateTime(LocalDateTime.now());
                babyAlbumMapper.updateById(album);
            }
        }

        return deleted;
    }

    /**
     * 设置相册封面
     *
     * @param babyId  宝宝ID
     * @param albumId 相册ID
     * @param photoId 照片ID
     * @return 修改后的相册
     */
    @Transactional(rollbackFor = Exception.class)
    public BabyAlbumVO setCover(Long babyId, Long albumId, Long photoId) {
        Long familyId = UserContext.getFamilyId();

        BabyAlbum album = babyAlbumMapper.selectOne(
                new LambdaQueryWrapper<BabyAlbum>()
                        .eq(BabyAlbum::getId, albumId)
                        .eq(BabyAlbum::getBabyId, babyId)
                        .eq(BabyAlbum::getFamilyId, familyId)
        );

        if (album == null) {
            return null;
        }

        BabyPhoto photo = babyPhotoMapper.selectOne(
                new LambdaQueryWrapper<BabyPhoto>()
                        .eq(BabyPhoto::getId, photoId)
                        .eq(BabyPhoto::getAlbumId, albumId)
                        .eq(BabyPhoto::getBabyId, babyId)
                        .eq(BabyPhoto::getFamilyId, familyId)
        );

        if (photo == null) {
            return null;
        }

        album.setCoverUrl(photo.getUrl());
        album.setUpdateTime(LocalDateTime.now());
        babyAlbumMapper.updateById(album);

        return convertAlbumToVO(album);
    }

    /**
     * 转换相册为VO
     */
    private BabyAlbumVO convertAlbumToVO(BabyAlbum album) {
        BabyAlbumVO vo = new BabyAlbumVO();
        vo.setId(album.getId());
        vo.setBabyId(album.getBabyId());
        vo.setFamilyId(album.getFamilyId());
        vo.setTitle(album.getTitle());
        vo.setDescription(album.getDescription());
        vo.setCoverUrl(album.getCoverUrl());
        vo.setPhotoCount(album.getPhotoCount());
        vo.setCreateTime(album.getCreateTime());
        vo.setUpdateTime(album.getUpdateTime());
        return vo;
    }

    /**
     * 转换照片为VO
     */
    private BabyPhotoVO convertPhotoToVO(BabyPhoto photo) {
        BabyPhotoVO vo = new BabyPhotoVO();
        vo.setId(photo.getId());
        vo.setBabyId(photo.getBabyId());
        vo.setAlbumId(photo.getAlbumId());
        vo.setFamilyId(photo.getFamilyId());
        vo.setUrl(photo.getUrl());
        vo.setThumbnailUrl(photo.getThumbnailUrl());
        vo.setTitle(photo.getTitle());
        vo.setDescription(photo.getDescription());
        vo.setPhotoDate(photo.getPhotoDate());
        vo.setUploaderId(photo.getUploaderId());
        vo.setFileSize(photo.getFileSize());
        vo.setWidth(photo.getWidth());
        vo.setHeight(photo.getHeight());
        vo.setMilestoneId(photo.getMilestoneId());
        vo.setSortOrder(photo.getSortOrder());
        vo.setCreateTime(photo.getCreateTime());
        vo.setUpdateTime(photo.getUpdateTime());
        return vo;
    }
}
