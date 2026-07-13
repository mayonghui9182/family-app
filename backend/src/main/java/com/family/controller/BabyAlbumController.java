package com.family.controller;

import com.family.common.result.Result;
import com.family.service.BabyAlbumService;
import com.family.vo.BabyAlbumVO;
import com.family.vo.BabyPhotoVO;
import com.family.vo.PageVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 宝宝相册控制器
 */
@Tag(name = "宝宝相册管理", description = "宝宝相册和照片相关接口")
@RestController
@RequestMapping("/baby/{babyId}")
@RequiredArgsConstructor
public class BabyAlbumController {

    private final BabyAlbumService babyAlbumService;

    /**
     * 相册列表
     *
     * @param babyId 宝宝ID
     * @return 相册列表
     */
    @Operation(summary = "获取相册列表", description = "获取宝宝的所有相册列表")
    @GetMapping("/albums")
    public Result<List<BabyAlbumVO>> getAlbumList(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId) {
        List<BabyAlbumVO> albums = babyAlbumService.getAlbumList(babyId);
        return Result.success(albums);
    }

    /**
     * 创建相册
     *
     * @param babyId      宝宝ID
     * @param title       标题
     * @param description 描述（可选）
     * @return 新建的相册
     */
    @Operation(summary = "创建相册", description = "创建一个新的宝宝相册")
    @PostMapping("/albums")
    public Result<BabyAlbumVO> createAlbum(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId,
            @Parameter(description = "相册标题")
            @RequestParam String title,
            @Parameter(description = "相册描述")
            @RequestParam(required = false) String description) {
        BabyAlbumVO album = babyAlbumService.createAlbum(babyId, title, description);
        return Result.success(album);
    }

    /**
     * 修改相册
     *
     * @param babyId      宝宝ID
     * @param albumId     相册ID
     * @param title       标题（可选）
     * @param description 描述（可选）
     * @return 修改后的相册
     */
    @Operation(summary = "修改相册", description = "修改相册的标题和描述")
    @PutMapping("/albums/{albumId}")
    public Result<BabyAlbumVO> updateAlbum(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId,
            @Parameter(description = "相册ID")
            @PathVariable Long albumId,
            @Parameter(description = "相册标题")
            @RequestParam(required = false) String title,
            @Parameter(description = "相册描述")
            @RequestParam(required = false) String description) {
        BabyAlbumVO album = babyAlbumService.updateAlbum(babyId, albumId, title, description);
        return Result.success(album);
    }

    /**
     * 删除相册
     *
     * @param babyId  宝宝ID
     * @param albumId 相册ID
     * @return 是否成功
     */
    @Operation(summary = "删除相册", description = "删除相册及其所有照片")
    @DeleteMapping("/albums/{albumId}")
    public Result<Boolean> deleteAlbum(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId,
            @Parameter(description = "相册ID")
            @PathVariable Long albumId) {
        boolean success = babyAlbumService.deleteAlbum(babyId, albumId);
        return Result.success(success);
    }

    /**
     * 照片列表（分页）
     *
     * @param babyId  宝宝ID
     * @param albumId 相册ID
     * @param page    页码
     * @param size    每页大小
     * @return 分页照片列表
     */
    @Operation(summary = "获取照片列表", description = "分页获取相册内的照片列表")
    @GetMapping("/albums/{albumId}/photos")
    public Result<PageVO<BabyPhotoVO>> getPhotoList(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId,
            @Parameter(description = "相册ID")
            @PathVariable Long albumId,
            @Parameter(description = "页码")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页大小")
            @RequestParam(defaultValue = "20") int size) {
        PageVO<BabyPhotoVO> photoPage = babyAlbumService.getPhotoList(babyId, albumId, page, size);
        return Result.success(photoPage);
    }

    /**
     * 上传照片
     *
     * @param babyId    宝宝ID
     * @param albumId   相册ID
     * @param photoUrls 照片URL列表
     * @param title     标题（可选）
     * @param photoDate 拍摄日期
     * @return 上传的照片列表
     */
    @Operation(summary = "上传照片", description = "批量上传照片到相册，接收已上传的URL列表")
    @PostMapping("/albums/{albumId}/photos")
    public Result<List<BabyPhotoVO>> uploadPhotos(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId,
            @Parameter(description = "相册ID")
            @PathVariable Long albumId,
            @Parameter(description = "照片URL列表")
            @RequestParam List<String> photoUrls,
            @Parameter(description = "照片标题")
            @RequestParam(required = false) String title,
            @Parameter(description = "拍摄日期")
            @RequestParam LocalDate photoDate) {
        List<BabyPhotoVO> photos = babyAlbumService.uploadPhotos(babyId, albumId, photoUrls, title, photoDate);
        return Result.success(photos);
    }

    /**
     * 修改照片
     *
     * @param babyId      宝宝ID
     * @param photoId     照片ID
     * @param title       标题（可选）
     * @param description 描述（可选）
     * @param photoDate   拍摄日期（可选）
     * @return 修改后的照片
     */
    @Operation(summary = "修改照片", description = "修改照片的标题、描述和拍摄日期")
    @PutMapping("/photos/{photoId}")
    public Result<BabyPhotoVO> updatePhoto(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId,
            @Parameter(description = "照片ID")
            @PathVariable Long photoId,
            @Parameter(description = "照片标题")
            @RequestParam(required = false) String title,
            @Parameter(description = "照片描述")
            @RequestParam(required = false) String description,
            @Parameter(description = "拍摄日期")
            @RequestParam(required = false) LocalDate photoDate) {
        BabyPhotoVO photo = babyAlbumService.updatePhoto(babyId, photoId, title, description, photoDate);
        return Result.success(photo);
    }

    /**
     * 删除照片
     *
     * @param babyId  宝宝ID
     * @param photoId 照片ID
     * @return 是否成功
     */
    @Operation(summary = "删除照片", description = "删除指定的照片")
    @DeleteMapping("/photos/{photoId}")
    public Result<Boolean> deletePhoto(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId,
            @Parameter(description = "照片ID")
            @PathVariable Long photoId) {
        boolean success = babyAlbumService.deletePhoto(babyId, photoId);
        return Result.success(success);
    }

    /**
     * 设置封面
     *
     * @param babyId  宝宝ID
     * @param albumId 相册ID
     * @param photoId 照片ID
     * @return 修改后的相册
     */
    @Operation(summary = "设置相册封面", description = "将指定照片设置为相册封面")
    @PutMapping("/albums/{albumId}/cover")
    public Result<BabyAlbumVO> setCover(
            @Parameter(description = "宝宝ID")
            @PathVariable Long babyId,
            @Parameter(description = "相册ID")
            @PathVariable Long albumId,
            @Parameter(description = "照片ID")
            @RequestParam Long photoId) {
        BabyAlbumVO album = babyAlbumService.setCover(babyId, albumId, photoId);
        return Result.success(album);
    }
}
